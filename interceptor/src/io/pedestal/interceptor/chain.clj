; Copyright 2023-2025 Nubank NA
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
            [io.pedestal.interceptor.impl :as impl]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor])
  (:import java.util.concurrent.atomic.AtomicLong
           (clojure.lang PersistentQueue)))

(declare ^:private execute-continue)


(defn- name-for
  [interceptor]
  ;; Generally, interceptors will have a :name key that's a keyword, but there still
  ;; (for some reason) the occasionally anonymous interceptor.
  (or (get interceptor :name)
      (pr-str interceptor)))

(defn- throwable->ex-info
  [^Throwable t execution-id interceptor-name stage]
  (let [throwable-str (pr-str (type t))]
    (ex-info (str throwable-str " in Interceptor " interceptor-name " - " (.getMessage t))
             (merge {:execution-id   execution-id
                     :stage          stage
                     :interceptor    interceptor-name
                     :exception-type (keyword throwable-str)
                     :exception      t}
                    (ex-data t))
             t)))

(defn with-error
  "Sets the provided exception as the ::error key of the context."
  {:added "0.8.0"}
  [context ^Throwable t]
  (assoc context ::error t))

(defn clear-error
  "Clears the error from the context."
  {:added "0.8.0"}
  [context]
  (dissoc context ::error))

(defn- begin-error
  [context stage interceptor throwable]
  (let [{:keys [execution-id]} context
        interceptor-name (name-for interceptor)]
    (log/debug :exception throwable
               :execution-id execution-id
               :interceptor interceptor-name
               :stage stage)
    (-> context
        (dissoc ::queue)
        (with-error (throwable->ex-info throwable
                                        execution-id
                                        interceptor-name
                                        stage)))))

(defn terminate
  "Removes all remaining interceptors from context's execution queue.
  This effectively short-circuits execution of Interceptors' :enter
  functions and begins executing the :leave functions."
  [context]
  ;; Note that this will do nothing during :leave stage
  (dissoc context ::queue))

(defn- check-terminators
  "Invokes each predicate in ::terminators on context. If any predicate
  returns truthy, terminates :enter stage execution."
  [_interceptor context]
  (if (some #(% context) (::terminators context))
    (terminate context)
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
  (if-let [callback (get interceptor stage)]
    (try
      (let [context-out (callback context)]
        ;; TODO: returning nil violates the interceptor contract; we could check here.
        (if (map? context-out)
          (cond->> (notify-observer interceptor stage context context-out)
                   ;; This step is duplicated in go-async:
                   ;; It has to be here, to properly report the exception
                   ;; if any terminator check fn throws.
                   (= stage :enter) (check-terminators interceptor))
          ;; Should be a channel
          context-out))
      (catch Throwable t
        (begin-error context stage interceptor t)))
    context))

(defn- try-error
  "Invokes the :error interceptor."
  [context interceptor error]
  (if-let [callback (get interceptor :error)]
    (let [context-in (dissoc context ::error)]
      (try
        (let [context-out (callback context-in error)]
          (notify-observer interceptor :error context-in context-out))
        (catch Throwable t
          (if (identical? (type t) (-> error ex-data :exception type))
            (do
              (log/debug :rethrow t :execution-id (::excecution-id context))
              context)
            (let [execution-id (::excecution-id context)]
              (log/debug :throw t :suppressed (:exception-type error) :execution-id execution-id)
              (-> context
                  (with-error (throwable->ex-info t execution-id (name-for interceptor) :error))
                  (update ::suppressed conj error)))))))
    context))

(defn- prepare-for-async
  "Calls each of the :enter-async functions in a context. The purpose of these
  functions is to ready backing servlets or any other machinery for preparing
  an asynchronous response."
  [{::keys [enter-async] :as context}]
  (doseq [enter-async-fn enter-async]
    (enter-async-fn context)))

(defn- process-async-context
  [new-context interceptor stage old-context]
  (if new-context
    (try
      (cond->> (notify-observer interceptor stage old-context new-context)
               (= stage :enter) (check-terminators interceptor))
      (catch Throwable t
        (let [execution-id (::excecution-id new-context)]
          (log/debug :exception t
                     :execution-id execution-id
                     :stage stage
                     :interceptor (name-for interceptor))
          (begin-error new-context stage interceptor t))))
    (begin-error old-context
                 stage
                 interceptor
                 (ex-info "Async Interceptor closed Context Channel before delivering a Context"
                          {:execution-id   (::execution-id old-context)
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
  (async/take! context-channel
               (fn [new-context]
                 (-> new-context
                     (process-async-context interceptor stage old-context)
                     (dissoc ::enter-async)
                     execute-continue)))
  ;; This nil will propagate all the way up, causing an immediate return from
  ;; chain/execute (which will return nil), while the actual processing continues in go threads.
  nil)

(defn- execute-enter
  [initial-context]
  ;; Note: after an async interceptor conveys the context, we'll go through here
  ;; even during the :leave stage, but the ::queue is gone, so we continue
  ;; through to the :leave stage.
  (if-not (-> initial-context ::queue seq)
    initial-context
    (let [*rebind? (volatile! false)]
      (loop [binding-context initial-context]               ;; outer loop
        (let [initial-bindings (:bindings binding-context)
              context'         (with-bindings (or initial-bindings {})
                                 (loop [context binding-context] ;; inner loop
                                   (let [queue       (::queue context)
                                         interceptor (peek queue)]
                                     (cond
                                       (nil? interceptor)
                                       (dissoc context ::queue)

                                       ;; When an interceptor changes the bindings, we must jump up a level
                                       ;; so that with-bindings can be called with the new bindings map.
                                       (not (identical? (:bindings context) initial-bindings))
                                       (do
                                         (vreset! *rebind? true)
                                         context)

                                       :else
                                       (let [stack       (::stack context)
                                             context'    (assoc context
                                                                ::queue (pop queue)
                                                                ::stack (conj stack interceptor))
                                             context-out (try-stage context' interceptor :enter)]
                                         (if (impl/channel? context-out)
                                           (go-async interceptor :enter context context-out)
                                           (recur context-out)))))))] ;; recur inner loop
          ;; inner loop may return early just to force a rebind when the :bindings
          ;; change, or may return nil if execution switched to async.
          (if @*rebind?
            (do
              (vreset! *rebind? false)
              (recur context'))                             ;; recur outer loop
            context'))))))

(defn- execute-leave
  "Invoked when :enter phase completes, either due to a terminator, the natural end of the queue,
  or when an exception is thrown."
  [initial-context]
  (let [*rebind? (volatile! false)]
    (loop [binding-context initial-context]                 ;; outer loop
      (let [initial-bindings (:bindings binding-context)
            context'         (with-bindings (or initial-bindings {})
                               (loop [context binding-context] ;; inner loop
                                 (let [queue       (::leave-queue context)
                                       interceptor (peek queue)]
                                   (cond
                                     (nil? interceptor)
                                     context

                                     ;; When an interceptor changes the bindings, we must jump up a level
                                     ;; so that with-bindings can be called with the new bindings map.
                                     (not (identical? (:bindings context) initial-bindings))
                                     (do
                                       (vreset! *rebind? true)
                                       context)

                                     :else
                                     (let [context'    (assoc context ::leave-queue (pop queue))
                                           error       (::error context)
                                           context-out (if error
                                                         (try-error context' interceptor error)
                                                         (try-stage context' interceptor :leave))]
                                       (if (impl/channel? context-out)
                                         (go-async interceptor :leave context context-out)
                                         (recur context-out)))))))] ;; recur inner loop
        ;; inner loop may return early just to force a rebind when the :bindings
        ;; change, or may return nil if execution switched to async.
        (if @*rebind?
          (do
            (vreset! *rebind? false)
            (recur context'))                               ;; recur outer loop
          context')))))

(defn- prepare-for-leave
  [context]
  ;; The ::stack exists during the enter stage and is removed here marking entry into the
  ;; :leave stage.  This may get invoked repeatedly when there are async interceptors.
  (if (contains? context ::stack)
    (let [stack (::stack context)]
      (-> context
          (assoc ::leave-queue (reverse stack))
          (dissoc ::stack)))
    ;; Leave it alone, ::stack has already been converted to ::leave-queue
    context))

(defn- into-queue
  [queue values]
  (into (or queue PersistentQueue/EMPTY) values))

(defn enqueue
  "Adds interceptors to the end of context's execution queue. Creates
  the queue if necessary. Returns updated context."
  [context interceptors]
  {:pre [(every? interceptor/interceptor? interceptors)]}
  (update context ::queue into-queue interceptors))

(defn enqueue*
  "Like [[enqueue]] but accepting a variable number of arguments.
  If the last argument is itself a sequence of interceptors,
  they're unpacked and added to the context's execution queue."
  [context & interceptors-and-seq]
  (if (sequential? (last interceptors-and-seq))
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

(defn- begin
  [context]
  (if (contains? context ::execution-id)
    context
    (assoc context ::execution-id (.incrementAndGet execution-id))))

(defn on-enter-async
  "Adds a callback function to be executed if the execution goes async, which occurs
  when an interceptor returns a core.async channel rather than a context map.

  The supplied function is appended to the list of such functions.
  All the functions are invoked, but only invoked once (a subsequent interceptor
  also returning a channel does not have this side effect).

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
                     execute-enter
                     prepare-for-leave
                     execute-leave)]
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
  re-throw the exception, or attach it as the ::error key
  (via the [[with-error]] function).

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
  a channel.

  Note that any previously queued interceptors are discarded when `execute` is invoked.
  "
  ([context]
   (-> context
       (assoc ::stack PersistentQueue/EMPTY)
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
  is conveyed through the returned core.async channel.

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
  can be used to log each interceptor that executes, in what stage it executes,
  and how it modifies the context map."
  [context observer-fn]
  (update context ::observer-fn merge-observer observer-fn))
