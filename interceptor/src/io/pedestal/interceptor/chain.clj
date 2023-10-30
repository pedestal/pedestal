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
  "Interceptor pattern. Executes a chain of Interceptor functions on a
  common \"context\" map, maintaining a virtual \"stack\", with error
  handling and support for asynchronous execution."
  (:refer-clojure :exclude (name))
  (:require [clojure.core.async :as async]
            [io.pedestal.internal :as i]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor])
  (:import java.util.concurrent.atomic.AtomicLong
           (clojure.core.async.impl.protocols Channel)))

(defn- channel? [c] (instance? Channel c))

(defn- needed-on-stack?
  [interceptor]
  (or (:leave interceptor) (:error interceptor)))

;; This is used for printing out interceptors within debug messages
(defn- name
  [interceptor]
  (get interceptor :name (pr-str interceptor)))

(defn- throwable->ex-info
  [^Throwable t execution-id interceptor stage]
  (let [iname (name interceptor)
        throwable-str (pr-str (type t))]
    (ex-info (str throwable-str " in Interceptor " iname " - " (.getMessage t))
             (merge {:execution-id execution-id
                     :stage stage
                     :interceptor iname
                     :exception-type (keyword throwable-str)
                     :exception t}
                    (ex-data t))
             t)))

(defn- try-f
  "If f is not nil, invokes it on context. If f throws an exception,
  assoc's it on to context as ::error."
  [context interceptor stage]
  (let [execution-id (::execution-id context)]
    (if-let [f (get interceptor stage)]
      (try (log/debug :interceptor (name interceptor)
                      :stage stage
                      :execution-id execution-id
                      :fn f)
           (f context)
           (catch Throwable t
             (log/debug :throw t :execution-id execution-id)
             (assoc context ::error (throwable->ex-info t execution-id interceptor stage))))
      (do (log/trace :interceptor (name interceptor)
                     :skipped? true
                     :stage stage
                     :execution-id execution-id)
          context))))

(defn- try-error
  "If error-fn is not nil, invoke it with the context and the current ::error
  from the context.  The interceptor can throw a new exception, add the current exception back
  to the context, or discard the exception."
  [context interceptor]
  (let [execution-id (::execution-id context)]
    (if-let [error-fn (get interceptor :error)]
      (let [ex (::error context)]
        (log/debug :interceptor (name interceptor)
                   :stage :error
                   :execution-id execution-id)
        (try (error-fn (dissoc context ::error) ex)
             (catch Throwable t
               (if (identical? (type t) (-> ex ex-data :exception type))
                 (do (log/debug :rethrow t :execution-id execution-id)
                     context)
                 (do (log/debug :throw t :suppressed (:exception-type ex) :execution-id execution-id)
                     (-> context
                         (assoc ::error (throwable->ex-info t execution-id interceptor :error))
                         ;; This doesn't seem to be used, maybe left around to debug some issues?
                         (update ::suppressed conj ex)))))))
      (do (log/trace :interceptor (name interceptor)
                     :skipped? true
                     :stage :error
                     :execution-id execution-id)
          context))))

(defn- check-terminators
  "Invokes each predicate in ::terminators with the context. If any predicate
  returns truthy, removes ::queue and ::terminators from context."
  [context]
  (let [terminators (::terminators context)]
    (if (some #(% context) terminators)
      (do
        (log/debug :in 'check-terminators
                   :terminate? true
                   :execution-id (::execution-id context))
        (dissoc context ::queue ::terminators))
      context)))

(declare ^:private invoke-interceptors-binder)

(defn- handle-async
  "Invoked when an interceptor returns an asynchronous result via channel, rather than a context map.

  context-ch: will convey context
  execution-context: context passed to the active interceptor
  active-interceptor: interceptor invoked
  continuation: the continuation map
  "
  [context-ch execution-context active-interceptor continuation]
  (log/debug :in 'handle-async
             :execution-id (::execution-id execution-context)
             :interceptor (name active-interceptor))
  ;; Invoke any callbacks the first time an async interceptor is called.
  ;; Assumption: on-enter-async is only invoked before `execute` (or at least, before the first
  ;; async interceptor is encountered).
  (doseq [enter-async-fn (::enter-async execution-context)]
    (enter-async-fn execution-context))
  (let [callback (fn [new-context]
                   (log/debug :in 'handle-async/callback
                              :thread-bindings (get-thread-bindings))
                   ;; Note: this will be invoked in a thread from the fix-sized core.async dispatch thread pool.
                   (if new-context
                     (-> new-context
                         (dissoc ::enter-async)
                         (assoc ::continuation continuation)
                         invoke-interceptors-binder)
                     ;; Otherwise, the interceptor closed the channel.
                     (let [{::keys [stage execution-id]} execution-context
                           error (ex-info "Async Interceptor closed Context Channel before delivering a Context"
                                          {:execution-id execution-id
                                           :stage stage
                                           :interceptor (name active-interceptor)
                                           :exception-type :PedestalChainAsyncPrematureClose})]
                       (invoke-interceptors-binder (-> execution-context
                                                       (dissoc ::enter-async)
                                                       (assoc ::error error
                                                              ::continuation continuation))))))]
    ;; On the CI server, keep seeing that the callback is invoked on a thread that has
    ;; some thread bindings already in place.
    (async/take! context-ch callback)
    ;; Expressly return nil to exit the original execution (it continues in
    ;; the core.async threads).
    nil))

(defn- next-interceptor
  "Gets the next interceptor from the queue, or returns nil if the queue is exhausted."
  [context queue-index]
  (let [queue (::queue context)]
    (when (and (seq queue)
               (< queue-index (count queue)))
      (nth queue queue-index))))

(defn- flip-stack-into-queue
  [context stack]
  (assoc context ::queue (into [] stack)))

(defn- enter-leave-stage
  [context stack]
  (-> context
      (flip-stack-into-queue stack)
      ;; terminators exist to prematurely transition into the leave stage;
      ;; if left around, they will trigger again, and prevent all :leave terminators
      ;; from being invoked.
      (dissoc ::terminators)
      (assoc ::stage :leave)))

(defn- invoke-interceptors
  "Invokes interceptors in the queue."
  [context]
  ;; The stage will be either :enter or :leave (errors are handled specially)
  (let [{::keys [stage continuation execution-id]
         entry-bindings :bindings} context
        enter? (= :enter stage)]
    (log/debug :in 'invoke-interceptors
               :handling stage
               :execution-id execution-id)
    (loop [{::keys [error]
            :as loop-context} (dissoc context ::continuation)
           queue-index (:queue-index continuation 0)
           stack (:stack continuation)]
      (log/trace :in 'invoke-interceptors
                 :stage stage
                 :execution-id execution-id
                 :context-keys (-> loop-context keys sort vec)
                 :stack (mapv name stack)
                 :queue (->> loop-context ::queue (drop queue-index) (mapv name))
                 :error? (contains? loop-context ::error)
                 :bindings-mismatch? (not= entry-bindings (:bindings loop-context)))
      ;; Handle the results of the prior interceptor invocation (whether we get here by a direct recur,
      ;; or indirectly through the async machinery).
      (cond
        (not= entry-bindings (:bindings loop-context))
        ;; Return up a level so that bindings can be adjusted,
        ;; and setup up the continuation to pick back up.
        (assoc loop-context
               ::rebind true
               ::continuation {:stack stack
                               :queue-index queue-index})

        ;; When an error first occurs during enter phase, treat that as a terminator and enter
        ;; the leave stage to process the error.
        (and error enter?)
        (invoke-interceptors (enter-leave-stage loop-context stack))

        :else
        (let [initial-context (check-terminators loop-context)
              interceptor (next-interceptor initial-context queue-index)]
          (if-not interceptor
            ;; Terminate when the queue is empty (possibly because a terminator did its job
            ;; and emptied it).
            (case stage
              :enter (invoke-interceptors (enter-leave-stage initial-context stack))
              :leave initial-context)
            (let [queue-index' (inc queue-index)
                  stack' (when enter?
                           (cond-> stack
                             (needed-on-stack? interceptor) (conj interceptor)))
                  result (if error
                           (try-error initial-context interceptor)
                           (try-f initial-context interceptor stage))]
              ;; result is either a context map, or a channel that will
              ;; convey the context map.
              (if (channel? result)
                ;; If the interceptor changes :bindings, that's ok, because after
                ;; the new context is conveyed; it will invoke invoke-interceptors-binder, to pick back up
                ;; where we left off, as if we had just recur'ed.
                (handle-async result
                              initial-context
                              interceptor
                              {:stack stack'
                               :queue-index queue-index'})
                (recur result queue-index' stack')))))))))

(defn- invoke-interceptors-only
  "Invokes interceptors in the queue, but only one direction (all :enter, or all :leave)."
  [context]
  ;; The stage will be either :enter or :leave (errors are handled specially)
  (let [{::keys [stage continuation execution-id]
         entry-bindings :bindings} context
        enter? (= :enter stage)]
    (log/debug :in 'invoke-interceptors-only
               :handling stage
               :execution-id execution-id)
    (loop [{::keys [resolving-error? error]
            :as loop-context} (dissoc context ::continuation)
           queue-index (:queue-index continuation 0)
           stack (:stack continuation)]
      (log/trace :in 'invoke-interceptors-only
                 :stage stage
                 :execution-id execution-id
                 :context-keys (-> loop-context keys sort vec)
                 :stack (mapv name stack)
                 :queue (->> loop-context ::queue (drop queue-index) (mapv name))
                 :resolving-error? resolving-error?
                 :error? (contains? loop-context ::error)
                 :bindings-mismatch? (not= entry-bindings (:bindings loop-context)))
      ;; Handle the results of the prior interceptor invocation (whether we get here by a direct recur,
      ;; or indirectly through the async machinery).
      (cond
        (not= entry-bindings (:bindings loop-context))
        ;; Return up a level so that bindings can be adjusted,
        ;; and setup up the continuation to pick back up.
        (assoc loop-context
               ::rebind true
               ::continuation {:stack stack
                               :queue-index queue-index})

        ;; When an error first occurs, switch resolving-error? on, which means we're attempting
        ;; to work back in the stack to find an interceptor that can handle the error.
        (and error
             (not resolving-error?))
        (invoke-interceptors-only (-> loop-context
                                      (cond-> enter? (enter-leave-stage stack))
                                      (assoc ::resolving-error? true)))

        ;  After resolving an error execution is complete.
        (and resolving-error?
             (not error))
        loop-context

        :else
        (let [initial-context (check-terminators loop-context)
              interceptor (next-interceptor initial-context queue-index)]
          (if-not interceptor
            ;; Terminate when the queue is empty (possibly because a terminator did its job
            ;; and emptied it).
            (case stage
              ;; At end of :enter, leave context in state to invoke execute-only :leave.
              :enter (flip-stack-into-queue initial-context stack)
              :leave initial-context)
            (let [queue-index' (inc queue-index)
                  stack' (when enter?
                           (cond-> stack
                             (needed-on-stack? interceptor) (conj interceptor)))
                  result (if error
                           (try-error initial-context interceptor)
                           (try-f initial-context interceptor stage))]
              ;; result is either a context map, or a channel that will
              ;; convey the context map.
              (if (channel? result)
                ;; If the interceptor changes :bindings, that's ok, because after
                ;; the new context is conveyed; it will invoke invoke-interceptors-binder, to pick back up
                ;; where we left off, as if we had just recur'ed.
                (handle-async result
                              initial-context
                              interceptor
                              {:stack stack'
                               :queue-index queue-index'})
                (recur result queue-index' stack')))))))))

(defn- invoke-interceptors-binder
  "Exists to support :bindings in the context; a wrapper around invoke-interceptors that
   handles a special case where invoke-interceptors returns early so that bindings can
   be re-bound."
  [context]
  (let [{:keys [bindings]
         ::keys [invoker]} context
        _ (log/trace :in 'invoke-interceptors-binder
                     :bindings bindings
                     :thread-bindings (keys (get-thread-bindings)))
        context' (if (seq bindings)
                   (with-bindings bindings
                     ;; Advance the execution until complete, until an exit to swap the bindings,
                     ;; or until the execution switches to async mode.
                     (invoker context))
                   (invoker context))
        {::keys [rebind on-complete]} context']
    (cond
      rebind
      (recur (dissoc context' ::rebind))

      ;; When going async in the original thread, the context will be nil and this
      ;; clause won't execute.  Ultimately, in a dispatch thread, we'll hit the end condition
      ;; and trigger the on-complete (which may rethrow an ::error stored in the context).
      on-complete
      (on-complete context')

      :else
      context')))

(defn enqueue
  "Adds interceptors to the end of context's execution queue. Creates
  the queue if necessary. Returns the updated context.

  Generally, interceptors are only added during the :enter phase."
  [context interceptors]
  {:pre [(every? interceptor/interceptor? interceptors)]}
  (log/trace :enqueue (map name interceptors) :context context)
  (update context ::queue i/into-vec interceptors))

(defn enqueue*
  "Like 'enqueue' but vararg.
  If the last argument is a sequence of interceptors,
  they're unpacked and to added to the context's execution queue."
  [context & interceptors-and-seq]
  (if (seq? (last interceptors-and-seq))
    (enqueue context (apply list* interceptors-and-seq))
    (enqueue context interceptors-and-seq)))

(defn terminate
  "Removes all remaining interceptors from context's execution queue.
  This effectively short-circuits execution of Interceptors' :enter
  functions and begins executing the :leave functions.

  Termination normally occurs when the queue of interceptors is exhausted,
  or when an interceptor throws an exception."
  [context]
  (log/trace :in 'terminate :context context)
  (dissoc context ::queue))

(defn terminate-when
  "Adds pred as a terminating condition of the context. pred is a
  function that takes a context as its argument. It will be invoked
  after every Interceptor's :enter function. If pred returns logical
  true, execution will stop at that Interceptor."
  [context pred]
  (update context ::terminators i/vec-conj pred))

(defn on-enter-async
  "Adds a callback function to be executed if the execution goes async, which occurs
  when an interceptor returns a channel rather than a context map.

  The supplied function is appended to the list of such functions.
  All the functions are invoked, but only invoked once (a subsequent interceptor
  also returning a channel does not have this side effect.

  The functions are passed the context, but any returned value is ignored."
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

(def ^:private ^AtomicLong execution-id (AtomicLong.))

(defn- end
  [context]
  (log/debug :in 'end :execution-id (::execution-id context) :context-keys (keys context))
  (log/trace :context context)
  (when (::error context)
    (throw (::error context)))
  (dissoc context ::execution-id ::on-complete))

(defn- begin
  [context]
  (let [execution-id (.incrementAndGet execution-id)]
    (log/debug :in 'begin :execution-id execution-id)
    (log/trace :context context)
    (assoc context ::execution-id execution-id
           ::on-complete end)))

(defn execute-only
  "Like `execute`, but only processes the interceptors in a single direction,
  using `interceptor-key` (i.e. :enter, :leave) to determine which functions
  to call.

  For :enter, the interceptor queue is executed to completion (or early termination),
  then replaced with a queue of interceptors to execute during :leave.

  For :leave, the interceptor queue is executed as is (this is a change from 0.6 and
  earlier, where the provided queue would be reversed).

  This function is deprecated in Pedestal 0.7.0, as there is no reason to use
  it."
  {:deprecated "0.7.0"}
  ([context interceptor-key]
   (some-> context
           begin
           (assoc ::stage interceptor-key
                  ::invoker invoke-interceptors-only
                  ::execute-single? true)
           invoke-interceptors-binder))
  ([context interceptor-key interceptors]
   (execute-only (enqueue context interceptors) interceptor-key)))

(defn execute
  "Executes a queue of Interceptors attached to the context. Context
  must be a map, Interceptors are added with 'enqueue'.

  An Interceptor is a map or map-like object with the keys :enter,
  :leave, and :error. The value of each key is a function; missing
  keys or nil values are ignored. When executing a context, first all
  the :enter functions are invoked in order. As this happens, the
  Interceptors are pushed on to a stack.

  When execution reaches the end of the queue, the stage switches
  to :leave and the previously executed interceptors are formed
  into a new queue (but ordered in reverse).

  Both the :enter and :leave functions are called on a single
  argument, the context map, and return an updated context.

  Normally, the final context is returned after all :enter, :leave
  (and :error) callbacks have been invoked. However, if any
  interceptor returns a channel to force async execution,
  then this function will return nil, as the final state of the
  context is not yet known.

  The final context may contain additional keys not present in the
  initial context, including those added by interceptors, and by
  Pedestal (which will be fully qualified keywords).

  If any Interceptor function throws an exception, execution stops and
  begins popping Interceptors off the stack and calling their :error
  functions. The :error function takes two arguments: the context and
  an exception. It may either handle the exception, in which case the
  execution continues with the next :leave function on the stack; or
  re-throw the exception, passing control to the :error function on
  the stack. If the exception reaches the end of the stack without
  being handled, execute will throw it."
  ([context]
   (some-> context
           begin
           (assoc ::stage :enter
                  ::invoker invoke-interceptors)
           invoke-interceptors-binder))
  ([context interceptors]
   (execute (enqueue context interceptors))))

