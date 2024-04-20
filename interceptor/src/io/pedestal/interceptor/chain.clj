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

(ns io.pedestal.interceptor.chain
  "The implementation of the interceptor pattern, where a context map is passed through
  callbacks supplied by an extensible queue of interceptors, with provisions for
  error handling, observations, asynchronous executions, and other factors."
  (:require [clojure.core.async :as async]
            [io.pedestal.internal :as i]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor])
  (:import java.util.concurrent.atomic.AtomicLong
           (clojure.core.async.impl.protocols Channel)
           (clojure.lang PersistentQueue)))

(declare ^:private execute-continue)

(defn- channel?
  [c]
  (instance? Channel c))

(defn- name-for
  [interceptor]
  ;; Generally, interceptors will have a :name key that's a keyword, but there still
  ;; (for some reason) the occasionally anonymous interceptor.
  (or (get interceptor :name)
      (pr-str interceptor)))

(defn- throwable->ex-info
  [^Throwable t execution-id interceptor stage]
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

(defn- begin-new-stage
  [context current-stage new-stage]
  (let [context' (if (= :enter current-stage)
                   (let [{::keys [stack]} context]
                     (-> context
                         (assoc ::queue (reverse stack))
                         (dissoc ::stack))))]
    (assoc context' ::stage new-stage)))

(defn- begin-error
  [context current-stage error]
  (-> context
      (begin-new-stage current-stage :error)
      (assoc ::error error)))

(defn terminate
  "Removes all remaining interceptors from context's execution queue.
  This effectively short-circuits execution of Interceptors' :enter
  functions and begins executing the :leave functions."
  [context]
  (log/trace :in 'terminate :context context)
  (let [{::keys [stage]} context]
    (assert (= :enter stage)
            "terminate may only be called when in execution stage :enter")
    (begin-new-stage context :enter :leave)))

(defn- check-terminators
  "Invokes each predicate in ::terminators on context. If any predicate
  returns truthy, terminates :enter stage execution."
  [interceptor context]
  (if (some #(% context) (::terminators context))
    (do (log/debug :in 'check-terminators
                   :interceptor (name-for interceptor)
                   :terminate? true
                   :execution-id (::execution-id context))
        (terminate context))
    context))

(defn- notify-observer
  [interceptor stage context-in context-out]
  (when (map? context-out)
    (let [{::keys [observer-fn]} context-out]
      (when observer-fn
        (let [event {:stage            stage
                     :execution-id     (::execution-id context-in)
                     :interceptor-name (name-for interceptor)
                     :context-in       context-in
                     :context-out      context-out}]
          (observer-fn event)))))
  context-out)

(defn- try-stage
  "Extracts the callback from an interceptor and invokes it if non-nil."
  [context interceptor stage]
  (let [execution-id (::execution-id context)]
    (if-let [callback (get interceptor stage)]
      (try
        (log/debug :interceptor (name-for interceptor)
                   :stage stage
                   :execution-id execution-id
                   :fn callback)
        (let [context-out (callback context)]
          ;; TODO: returning nil violates the interceptor contract; we could check here.
          (if (map? context-out)
            (cond->> (notify-observer interceptor stage context context-out)
                     ;; This step is duplicated in go-async:
                     (= stage :enter) (check-terminators interceptor))
            ;; Should be a channel
            context-out))
        (catch Throwable t
          (log/debug :throw t :execution-id execution-id)
          (begin-error context stage (throwable->ex-info t execution-id interceptor stage))))
      (do (log/trace :interceptor (name-for interceptor)
                     :skipped? true
                     :stage stage
                     :execution-id execution-id)
          context))))

(defn- try-error
  "Invokes the :error callback of an interceptor if non-nil."
  [context interceptor]
  (if-let [callback (get interceptor :error)]
    (let [ex           (::error context)
          execution-id (::excecution-id context)
          ;; Hide the old error from the observer, if any.
          ;; The old-context is what is passed to the interceptor, so that's fair.
          context-in   (dissoc context ::error)]
      (log/debug :interceptor (name-for interceptor)
                 :stage :error
                 :execution-id execution-id)
      (try (let [context-out (callback context-in ex)]
             (cond->> context-out
                      (map? context-out) (notify-observer interceptor :error context-in)))
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
                   :execution-id (::execution-id context))
        context)))

(defn- prepare-for-async
  "Calls each of the :enter-async functions in a context. The purpose of these
  functions is to ready backing servlets or any other machinery for preparing
  an asynchronous response."
  [{::keys [enter-async] :as context}]
  (doseq [enter-async-fn enter-async]
    (enter-async-fn context)))

(defn- process-async-context
  [interceptor stage old-context new-context]
  (if new-context
    (try
      (cond->> (notify-observer interceptor stage old-context new-context)
               (= stage :enter) (check-terminators interceptor))
      (catch Throwable t
        (let [execution-id (::excecution-id new-context)]
          (log/debug :throw t :execution-id execution-id)
          (begin-error new-context stage
                       (throwable->ex-info t execution-id interceptor stage)))))
    (begin-error old-context stage (ex-info "Async Interceptor closed Context Channel before delivering a Context"
                                            {:execution-id   (::execution-id old-context)
                                             :stage          stage
                                             :interceptor    (name-for interceptor)
                                             :exception-type :PedestalChainAsyncPrematureClose}))))

(defn- go-async
  "When presented with a channel as the return value of an enter function,
  wait for the channel to return a new-context (via a go block). When a new
  context is received, restart execution of the interceptor chain with that
  context.

  This function is non-blocking, returning nil immediately (a signal to halt
  further execution on this thread)."
  [interceptor stage old-context context-channel]
  (prepare-for-async old-context)
  (let [callback (fn [new-context]
                   (-> (process-async-context interceptor stage old-context new-context)
                       (dissoc ::enter-async)
                       execute-continue))]
    ;; Wait for the new context to be conveyed.  Don't execute on this thread; we want this thread
    ;; to go back to the servlet container immediately.
    (async/take! context-channel callback false))
  ;; This nil will propagate all the way up, causing an immediate return from
  ;; chain/execute (which will return nil), while the actual processing continues in go threads.
  nil)

(defn- execute-interceptors-with-bindings
  [initial-context initial-bindings]
  (loop [context initial-context]
    (let [queue         (::queue context)
          stage         (::stage context)
          enter?        (= :enter stage)
          interceptor   (peek queue)
          is-exhausted? (nil? interceptor)]
      (cond

        is-exhausted?
        (if enter?
          ;; Exhausted :enter stage, start working backwards
          (recur (begin-new-stage context stage :leave))
          (assoc context ::complete? true))

        ;; When an interceptor changes the bindings, we must jump up a level
        ;; so that with-bindings can be called with the new bindings map.
        (not (identical? (:bindings context) initial-bindings))
        context

        (and (= :error stage)
             (-> context ::error nil?))
        (recur (assoc context ::stage :leave))

        :else
        (let [context'    (cond-> (update context ::queue pop)
                            enter? (update ::stack conj interceptor))
              ;; Possible optimizations:
              ;; - only push interceptor to stack if has :leave or :error
              ;; - only invoke try-stage/try-error if non-nil
              context-out (if (not= :error stage)
                            (try-stage context' interceptor stage)
                            (try-error context' interceptor))]
          (if (channel? context-out)
            (go-async interceptor stage context context-out)
            (recur context-out)))))))

(defn- execute-interceptors
  [initial-context]
  (loop [context initial-context]
    (let [{:keys [bindings]} context
          context' (with-bindings (or bindings {})
                     (execute-interceptors-with-bindings context bindings))]
      ;; execute-inner may return early just to force a rebind when the :bindings
      ;; change, or may return nil if execution switched to async.
      (if (and (some? context')
               (not (::complete? context')))
        (recur context')
        context'))))

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

(defn- setup-execution
  [context]
  (assoc context
         ::stack PersistentQueue/EMPTY
         ::stage :enter))

(defn- end
  "Called at end of execution, either in the original thread, or in a core.async thread."
  [context]
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

(defn- execute-continue
  "This is where things pick back up after going async."
  [context]
  (let [context' (-> context
                     execute-interceptors
                     end)]
    ;; Note that in async case, throwing an exception will occur in a core.async thread
    ;; with no hope of it being caught. Generally, it is expected that the interceptor chain
    ;; has at least one interceptor to handle otherwise uncaught exceptions.
    (if-let [ex (::error context')]
      (throw ex)
      context')))

(defn execute
  "Executes a queue of [[Interceptor]]s attached to the context. Context
  must be a map, Interceptors are added with 'enqueue'.

  An Interceptor is record with the keys :enter,
  :leave, and :error. The value of each key is a callback function; missing
  keys or nil values are ignored.

  Each Interceptor may also have a :name, which is used when logging.
  This is encouraged.

  When executing a context, first all
  the :enter functions are invoked in order.

  When :enter execution reaches the end of the queue, it switches
  to the :leave stage.  Each Interceptor's :leave function is invoked,
  in the opposite order from the :enter phase.

  Both the :enter and :leave functions are passed a single
  argument, the context map, and return an updated context.

  If any Interceptor function throws an exception, execution
  immediately switches to the :error stage. Interceptors are
  now considered in reverse order (like the :leave stage),
  but the :error callback (if non-nil) is passed the context and a
  wrapped version of the caught exception.

  The :error callback may either handle the exception, in which case the
  execution switches to the :leave stage; or the callback may
  re-throw the exception, or attach it as the ::error key.

  If the exception reaches the end of the stack without
  being handled, `execute` will throw it.

  Interceptor callbacks may return a core.async channel; this represents an
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
   (-> context
       setup-execution
       begin
       execute-continue))
  ([context interceptors]
   (execute (enqueue context interceptors))))

(defn ^{:added "0.7.0"} queue
  "Returns the contents of the queue, the as-yet uninvoked interceptors during the :enter phase
  of chain execution.

  Prior to 0.7.0, this was achieved by accessing the :io.pedestal.interceptor.chain/queue key;
  future enhancements may change how the interceptor queue and stack are stored."
  [context]
  ;; TODO: Return nil unless :enter, drop leading values (that have already executed)
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
  and how it modifies the context map."
  [context observer-fn]
  (update context ::observer-fn merge-observer observer-fn))
