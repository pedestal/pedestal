; Copyright 2023-2024 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

;; NOTE: This is the original implementation and has been rewritten.
;; This copy is being kept for comparison purposes only.

(ns io.pedestal.interceptor.chain1
  "Interceptor pattern. Executes a chain of Interceptor functions on a
  common \"context\" map, maintaining a virtual \"stack\", with error
  handling and support for asynchronous execution."
  (:require [clojure.core.async :refer [<! go]]
            [io.pedestal.internal :as i]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor])
  (:import java.util.concurrent.atomic.AtomicLong
           (clojure.core.async.impl.protocols Channel)
           (clojure.lang PersistentQueue)))

(declare execute)
(declare execute-only)

(defn- channel? [c] (instance? Channel c))

(defn- name-for
  [interceptor]
  ;; Generally, interceptors will have a :name key that's a keyword, but there still
  ;; (for some reason) the occasionally anonymous interceptor.
  (or (get interceptor :name)
      (pr-str interceptor)))

(defn- throwable->ex-info [^Throwable t execution-id interceptor stage]
  (let [iname         (name-for interceptor)
        throwable-str (pr-str (type t))]
    (ex-info (str throwable-str " in Interceptor " iname " - " (.getMessage t))
             (merge {:execution-id   execution-id
                     :stage          stage
                     :interceptor    iname
                     :exception-type (keyword throwable-str)
                     :exception      t}
                    (ex-data t))
             t)))

(defn- check-terminators
  "Invokes each predicate in ::terminators on context. If any predicate
  returns true, removes ::queue from context."
  [interceptor context]
  (if (some #(% context) (::terminators context))
    (do
      (log/debug :in 'check-terminators
                 :interceptor (name-for interceptor)
                 :terminate? true
                 :execution-id (::execution-id context))
      (dissoc context ::queue))
    context))

(defn- notify-observer
  [interceptor stage context-in context-out]
  (let [{::keys [observer-fn execution-id]} context-out]
    (when observer-fn
      (let [event {:stage            stage
                   :execution-id     execution-id
                   :interceptor-name (name-for interceptor)
                   :context-in       context-in
                   :context-out      context-out}]
        (observer-fn event))))
  context-out)

(defn- try-f
  "If f is not nil, invokes it on context. If f throws an exception,
  assoc's it on to context as ::error.  Returns the context map, or
  a channel that conveys the context map."
  [context interceptor stage]
  (let [execution-id (::execution-id context)]
    (if-let [f (get interceptor stage)]
      (try
        (log/debug :interceptor (name-for interceptor)
                      :stage stage
                      :execution-id execution-id
                      :fn f)
        (let [context-out (f context)]
          (if (map? context-out)
            (cond->> (notify-observer interceptor stage context context-out)
                     (= stage :enter) (check-terminators interceptor))
            ;; Should be a channel
            context-out))
        (catch Throwable t
          (log/debug :throw t :execution-id execution-id)
          (assoc context ::error (throwable->ex-info t execution-id interceptor stage))))
      (do (log/trace :interceptor (name-for interceptor)
                     :skipped? true
                     :stage stage
                     :execution-id execution-id)
          context))))

(defn- try-error
  "If error-fn is not nil, invokes it on context and the current ::error
  from context."
  [context interceptor]
  (let [execution-id (::execution-id context)]
    (if-let [error-fn (get interceptor :error)]
      (let [ex (::error context)
            ;; Hide the old error from the observer, if any.
            ;; The old-context is what is passed to the interceptor, so that's fair.
            old-context (dissoc context ::error)]
        (log/debug :interceptor (name-for interceptor)
                   :stage :error
                   :execution-id execution-id)
        (try (->> (error-fn old-context ex)
                  (notify-observer interceptor :error old-context))
             (catch Throwable t
               (if (identical? (type t) (-> ex ex-data :exception type))
                 (do (log/debug :rethrow t :execution-id execution-id)
                     context)
                 (do (log/debug :throw t :suppressed (:exception-type ex) :execution-id execution-id)
                     (-> context
                         (assoc ::error (throwable->ex-info t execution-id interceptor :error))
                         (update ::suppressed conj ex)))))))
      (do (log/trace :interceptor (name-for interceptor)
                     :skipped? true
                     :stage :error
                     :execution-id execution-id)
          context))))

(defn- prepare-for-async
  "Calls all of the :enter-async functions in a context. The purpose of these
  functions is to ready backing servlets or any other machinery for preparing
  an asynchronous response."
  [{::keys [enter-async] :as context}]
  (doseq [enter-async-fn enter-async]
    (enter-async-fn context)))

(defn- go-async
  "When presented with a channel as the return value of an enter function,
  wait for the channel to return a new-context (via a go block). When a new
  context is received, restart execution of the interceptor chain with that
  context.

  This function is non-blocking, returning nil immediately (a signal to halt
  further execution on this thread)."
  [interceptor stage stack old-context context-channel]
  (prepare-for-async old-context)
  (go
    (if-let [new-context (<! context-channel)]
      (let [new-context' (try
                           (cond->> (notify-observer interceptor stage old-context new-context)
                                    (= stage :enter) (check-terminators interceptor))
                           (catch Throwable t
                             (let [{:keys [execution-id]} new-context]
                               (log/debug :throw t :execution-id execution-id)
                               (assoc new-context ::error (throwable->ex-info t execution-id interceptor stage)))))]
        (execute (dissoc new-context' ::enter-async)))
      (execute (assoc (dissoc old-context ::queue ::enter-async)
                      ::stack stack
                      ::error (ex-info "Async Interceptor closed Context Channel before delivering a Context"
                                       {:execution-id   (::execution-id old-context)
                                        :stage          stage
                                        :interceptor    (name-for interceptor)
                                        :exception-type :PedestalChainAsyncPrematureClose})))))
  ;; This nil will propagate all the way up, causing an immediate return from
  ;; chain/execute (which return nil), while the actual processing continues in go threads.
  nil)

(defn- process-all-with-binding
  "Invokes `interceptor-key` functions of all Interceptors on the execution
  ::queue of context, saves them on the ::stack of context.
  Returns updated context."
  ([initial-context]
   (process-all-with-binding initial-context :enter))
  ([initial-context stage]
   (log/debug :in 'process-all :handling stage :execution-id (::execution-id initial-context))
   (let [pre-bindings (:bindings initial-context)]
     (loop [context initial-context]
       (cond
         (::error context)
         (dissoc context ::queue)

         (-> context ::queue empty?)
         context

         (not= (:bindings context) pre-bindings)
         ;; Return up a level to rebind, then reenter
         (assoc context ::rebind true)

         :else
         (let [{::keys [stack queue]} context]
           (log/trace :context context)
           (let [interceptor (peek queue)
                 new-queue   (pop queue)
                 ;; conj on nil returns a list, acts like a stack:
                 new-stack   (conj stack interceptor)
                 new-context (-> context
                                 (assoc ::queue new-queue
                                        ::stack new-stack)
                                 (try-f interceptor stage))]
             (if (channel? new-context)
               (go-async interceptor stage new-stack context new-context)
               (recur new-context)))))))))

(defn- process-all
  [context interceptor-key]
  ;; If we're processing leave handlers, reverse the queue
  (let [context (if (= interceptor-key :leave) (update context ::queue reverse) context)
        context (with-bindings (or (:bindings context)
                                   {})
                  (process-all-with-binding context interceptor-key))]
    (if (::rebind context)
      (recur (dissoc context ::rebind) interceptor-key)
      context)))

(defn- process-any-errors-with-binding
  "Unwinds the context by invoking :error functions of Interceptors on
  the ::stack of context, but **only** if there is an ::error present in the context."
  [initial-context]
  (log/debug :in 'process-any-errors :execution-id (::execution-id initial-context))
  (let [pre-bindings (:bindings initial-context)]
    (loop [context initial-context]
      (let [stack (::stack context)]
        (cond
          (empty? stack)
          context

          (not= pre-bindings (:bindings context))
          (assoc context ::rebind true)

          :else
          (let [_           (log/trace :context context)
                interceptor (peek stack)
                stack'      (pop stack)
                context'    (assoc context ::stack stack')
                new-context (if (::error context')
                              (try-error context' interceptor)
                              context')]
            (if (channel? new-context)
              (go-async interceptor :error stack' context' new-context)
              (recur new-context))))))))

(defn- process-any-errors
  "Establish the bindings present in `context` as thread local
  bindings, and then invoke process-any-errors-with-binding.
  Conditionally re-establish bindings if a change in bindings is made by an
  interceptor."
  [context]
  (let [context (with-bindings (or (:bindings context)
                                   {})
                  (process-any-errors-with-binding context))]
    (if (::rebind context)
      (recur (dissoc context ::rebind))
      context)))

(defn- enter-all
  "Establish the bindings present in `context` as thread local
  bindings, and then invoke enter-all-with-binding. Conditionally
  re-establish bindings if a change in bindings is made by an
  interceptor."
  [context]
  (process-all context :enter))

(defn- leave-all-with-binding
  "Unwinds the context by invoking :leave functions of Interceptors on
  the ::stack of context. Returns updated context."
  [initial-context]
  (log/debug :in 'leave-all :execution-id (::execution-id initial-context))
  (let [pre-bindings (:bindings initial-context)]
    (loop [context initial-context]
      (let [stack (::stack context)]
        (log/trace :context context)
        (cond
          (empty? stack)
          context

          (not= pre-bindings (:bindings context))
          (assoc context ::rebind true)

          :else
          (let [interceptor (peek stack)
                stack'      (pop stack)
                context'    (assoc context ::stack stack')
                new-context (if (::error context')
                              (try-error context' interceptor)
                              (try-f context' interceptor :leave))]
            (if (channel? new-context)
              (go-async interceptor :leave stack' context new-context)
              (recur new-context))))))))

(defn- leave-all
  "Establish the bindings present in `context` as thread local
  bindings, and then invoke leave-all-with-binding. Conditionally
  re-establish bindings if a change in bindings is made by an
  interceptor."
  [context]
  (let [context' (with-bindings (or (:bindings context)
                                    {})
                  (leave-all-with-binding context))]
    (if (::rebind context')
      (recur (dissoc context' ::rebind))
      context')))

(defn- into-queue
  [queue values]
  (into (or queue PersistentQueue/EMPTY) values))

(defn enqueue
  "Adds interceptors to the end of context's execution queue. Creates
  the queue if necessary. Returns updated context."
  [context interceptors]
  {:pre [(every? interceptor/interceptor? interceptors)]}
  (log/trace :enqueue (map name-for interceptors) :context context)
  (update context ::queue into-queue interceptors))

(defn enqueue*
  "Like 'enqueue' but vararg.
  If the last argument is itself a sequence of interceptors,
  they're unpacked and added to the context's execution queue."
  [context & interceptors-and-seq]
  (if (seq? (last interceptors-and-seq))
    (enqueue context (apply list* interceptors-and-seq))
    (enqueue context interceptors-and-seq)))

(defn terminate
  "Removes all remaining interceptors from context's execution queue.
  This effectively short-circuits execution of Interceptors' :enter
  functions and begins executing the :leave functions."
  [context]
  (log/trace :in 'terminate :context context)
  (dissoc context ::queue))

(defn terminate-when
  "Adds a predicate establishing a terminating condition for execution of the interceptor chain.

  pred is a function that takes a context as its argument. It will be invoked
  after every Interceptor's :enter function. If pred returns logical
  true, execution will stop at that Interceptor."
  [context pred]
  (update context ::terminators conj pred))

(def ^:private ^AtomicLong execution-id (AtomicLong.))

(defn- begin [context]
  (if (contains? context ::execution-id)
    context
    (let [execution-id (.incrementAndGet execution-id)]
      (log/debug :in 'begin :execution-id execution-id)
      (log/trace :context context)
      (assoc context ::execution-id execution-id))))

(defn- end [context]
  (if (contains? context ::execution-id)
    (do
      (log/debug :in 'end :execution-id (::execution-id context) :context-keys (keys context))
      (log/trace :context context)
      (dissoc context ::stack ::execution-id))
    context))

(defn on-enter-async
  "Adds a callback function to be executed if the execution goes async, which occurs
  when an interceptor returns a channel rather than a context map.

  The supplied function is appended to the list of such functions.
  All the functions are invoked, but only invoked once (a subsequent interceptor
  also returning a channel does not have this side effect.

  The callback function will be passed the context, but any returned value from the function is ignored."
  {:added "0.7.0"}
  [context f]
  (update context ::enter-async i/vec-conj f))

(defmacro bind
  "Updates the context to add a binding of the given var and value.
   This is a convenience on modifying the :bindings key (a map of Vars and values).

   Bound values will be available in subsequent interceptors."
  {:added "0.7.0"}
  [context var value]
  `(update ~context :bindings assoc (var ~var) ~value))

(defmacro unbind
  "Updates the context to remove a previous binding."
  {:added "0.7.0"}
  [context var]
  `(update ~context :bindings dissoc (var ~var)))

(defn ^{:deprecated "0.7.0"} execute-only
  "Like [[execute]], but only processes the interceptors in a single direction,
  using `interceptor-key` (i.e. :enter, :leave) to determine which functions
  to call.

  Executes a queue of Interceptors attached to the context. Context
  must be a map, Interceptors are added with 'enqueue'.

  An Interceptor Record has keys :enter, :leave, and :error.
  The value of each key is a function; missing
  keys or nil values are ignored. When executing a context, all
  the `interceptor-key` functions are invoked in order. As this happens, the
  Interceptors are pushed on to a stack."
  ([context interceptor-key]
   (i/deprecated `execute-only
     (let [context (some-> context
                           begin
                           (process-all interceptor-key)
                           terminate
                           process-any-errors
                           end)]
       (if-let [ex (::error context)]
         (throw ex)
         context))))
  ([context interceptor-key interceptors]
   (execute-only (enqueue context interceptors) interceptor-key)))

(defn execute
  "Executes a queue of [[Interceptor]]s attached to the context. Context
  must be a map, Interceptors are added with 'enqueue'.

  An Interceptor is record with the keys :enter,
  :leave, and :error. The value of each key is a function; missing
  keys or nil values are ignored. When executing a context, first all
  the :enter functions are invoked in order. As this happens, the
  Interceptors are pushed on to a stack.  Interceptor may also have a :name,
  which is used when logging.

  When execution reaches the end of the queue, it begins popping
  Interceptors off the stack and calling their :leave functions.
  Therefore :leave functions are called in the opposite order from
  :enter functions.

  Both the :enter and :leave functions are passed a single
  argument, the context map, and return an updated context.

  If any Interceptor function throws an exception, execution stops and
  begins popping Interceptors off the stack and calling their :error
  functions. The :error function takes two arguments: the context and
  an exception. It may either handle the exception, in which case the
  execution continues with the next :leave function on the stack; or
  re-throw the exception, passing control to the :error function on
  the stack. If the exception reaches the end of the stack without
  being handled, execute will throw it

  Functions may return a core.async channel; this represents an
  asynchronous process.  When this happens, the initial call to
  execute returns nil immediately, with the process exepected to write
  an updated context into the channel when its work completes.

  The function [[on-enter-async]] is used to provide a callback for
  when an interceptor chain execution first switches from in-thread to
  asynchronous execution.

  Processing continues in core.async threads - including even when
  a later interceptor returns an immediate context, rather than
  a channel."
  ([context]
   (let [context (some-> context
                         begin
                         enter-all
                         terminate
                         leave-all
                         end)]
     (if-let [ex (::error context)]
       (throw ex)
       context)))
  ([context interceptors]
   (execute (enqueue context interceptors))))

(defn ^{:added "0.7.0"} queue
  "Returns the contents of the queue, the as-yet uninvoked interceptors during the :enter phase
  of chain execution.

  Prior to 0.7.0, this was achieved by accessing the :io.pedestal.interceptor.chain/queue key;
  future enhancements may change how the interceptor queue and stack are stored."
  [context]
  (::queue context))

(defn- merge-observer
  [old-fn new-fn]
  (if old-fn
    (fn [event]
      (old-fn event)
      (new-fn event))
    new-fn))

(defn ^{:added "0.7.0"} add-observer
  "Adds an observer function to the execution; observer functions are notified after each interceptor
  executes.  If the interceptor is asynchronous, the notification occurs once the new context
  is conveyed through the returned channel.

  The function is passed an event map:

  Key               | Type              | Description
  ---               |---                |---
  :execution-id     | integer           | Unique per-process id for the execution
  :stage            | :enter, :leave, or :error
  :interceptor-name | keyword or string | The interceptor that was invoked (either its :name or a string)
  :context-in       | map               | The context passed to the interceptor
  :context-out      | map               | The context returned from the interceptor

  The observer is only invoked for interceptor _executions_; when an interceptor does not provide a callback
  for a stage, the interceptor is not invoked, and so the observer is not invoked.

  The value returned by the observer is ignored.

  If an observer throws an exception, it is associated with the interceptor, exactly as if the interceptor
  had thrown the exception.

  When multiple observer functions are added, they are invoked in an unspecified order.

  The [[debug-observer]] function is used to create an observer function; this observer
  can be used to log each interceptor that executes, in what phase it executes,
  and how it modifies the context map.."
  [context observer-fn]
  (update context ::observer-fn merge-observer observer-fn))
