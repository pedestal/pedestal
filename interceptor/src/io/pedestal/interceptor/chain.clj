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
  (:require [clojure.core.async :as async :refer [<! go]]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor])
  (:import java.util.concurrent.atomic.AtomicLong
           (clojure.core.async.impl.protocols Channel)))

#_(declare execute)
#_(declare execute-only)

(defn- channel? [c] (instance? Channel c))

(defn- needed-on-stack? [interceptor]
  (or (:leave interceptor) (:error interceptor)))

;; This is used for printing out interceptors within debug messages
(defn- name [interceptor]
  (get interceptor :name (pr-str interceptor)))

(defn- throwable->ex-info [^Throwable t execution-id interceptor stage]
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
  "If error-fn is not nil, invoke it on context and the current ::error
  from context."
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
                         (update ::suppressed conj ex)))))))
      (do (log/trace :interceptor (name interceptor)
                     :skipped? true
                     :stage :error
                     :execution-id execution-id)
          context))))

(defn- check-terminators
  "Invokes each predicate in ::terminators on context. If any predicate
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

(defn- prepare-for-async
  "Calls all :enter-async functions in a context. The purpose of these
  functions is to ready backing servlets or any other machinery for preparing
  an asynchronous response."
  [context]
  (doseq [enter-async-fn (:enter-async context)]
    (enter-async-fn context))
  (dissoc context :enter-async))

#_(defn- go-async
    "When presented with a channel as the return value of an enter function,
    wait for the channel to return a new-context (via a go block). When a new
    context is received, restart execution of the interceptor chain with that
    context.

    This function is non-blocking, returning nil immediately (a signal to halt
    further execution on this thread)."
    ([old-context context-channel]
     (prepare-for-async old-context)
     (go
       (if-let [new-context (<! context-channel)]
         ;; TODO: no allowance is made here for a async interceptor that fails, and
         ;; even if it attaches ::error to new-context, that may not be processed
         ;; until after the next interceptor is invoked.
         (execute new-context)
         (execute (assoc (dissoc old-context ::queue ::async-info)
                         ::stack (get-in old-context [::async-info :stack])
                         ::error (ex-info "Async Interceptor closed Context Channel before delivering a Context"
                                          {:execution-id (::execution-id old-context)
                                           :stage (get-in old-context [::async-info :stage])
                                           :interceptor (name (get-in old-context [::async-info :interceptor]))
                                           :exception-type :PedestalChainAsyncPrematureClose})))))
     nil)
    ([old-context context-channel interceptor-key]
     (prepare-for-async old-context)
     (go
       (if-let [new-context (<! context-channel)]
         (execute-only new-context interceptor-key)
         (execute-only (assoc (dissoc old-context ::queue ::async-info)
                              ::stack (get-in old-context [::async-info :stack])
                              ::error (ex-info "Async Interceptor closed Context Channel before delivering a Context"
                                               {:execution-id (::execution-id old-context)
                                                :stage (get-in old-context [::async-info :stage])
                                                :interceptor (name (get-in old-context [::async-info :interceptor]))
                                                :exception-type :PedestalChainAsyncPrematureClose}))
                       interceptor-key)))
     nil))

#_(defn- process-all-with-binding
    "Invokes `interceptor-key` (:enter or :leave)  functions of all Interceptors on the execution
    ::queue of context, saves them on the ::stack of context.
    Returns updated context.
    By default, `interceptor-key` is :enter"
    ([context]
     (process-all-with-binding context :enter))
    ([context interceptor-key]
     (log/debug :in 'process-all :handling interceptor-key :execution-id (::execution-id context))
     (loop [context (check-terminators context)]
       (let [queue (::queue context)]
         (log/trace :context context)
         (if (empty? queue)
           context
           (let [interceptor (peek queue)
                 pre-bindings (:bindings context)
                 new-queue (pop queue)
                 prepped-context (cond-> (assoc context ::queue new-queue)
                                   ;; conj on nil returns a list, acts like a stack:
                                   (needed-on-stack? interceptor) (update ::stack conj interceptor))
                 ;; Let the interceptor operate
                 context' (try-f prepped-context interceptor interceptor-key)]
             (cond
               (channel? context') (go-async (assoc context
                                                    ::async-info {:interceptor interceptor
                                                                  :stage interceptor-key
                                                                  :stack (::stack prepped-context)})
                                             context')
               (::error context') (dissoc context' ::queue)
               ;; When bindings change, have to pop-out of the most recent `binding` block to establish
               ;; the new bindings.
               (not= (:bindings context') pre-bindings) (assoc context' ::rebind true)
               true (recur (check-terminators context')))))))))

(declare ^:private invoke-interceptors-binder)

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

(defn- handle-async
  "Invoked when an interceptor returns a channel, rather than a context map.

  context-ch: will convey context
  execution-context: context passed to the active interceptor
  active-interceptor: interceptor invoked
  continuation: the continuation map
  "
  [context-ch execution-context active-interceptor continuation]
  (log/debug :in 'handle-async
             :execution-id (::execution-id execution-context)
             :interceptor (name active-interceptor))
  (let [context' (prepare-for-async execution-context)]
    (async/take! context-ch
                 (fn [new-context]
                   ;; Note: this will be invoked in a thread from the fix-sized core.async dispatch thread pool.
                   (if new-context
                     (-> new-context
                         (assoc ::continuation continuation)
                         invoke-interceptors-binder)
                     ;; Otherwise, the interceptor closed the channel.
                     (let [{::keys [stage execution-id]} context'
                           error (ex-info "Async Interceptor closed Context Channel before delivering a Context"
                                          {:execution-id execution-id
                                           :stage stage
                                           :interceptor (name active-interceptor)
                                           :exception-type :PedestalChainAsyncPrematureClose})]
                       (invoke-interceptors-binder (assoc context' ::error error
                                                          ::continuation continuation)))))))
  ;; Expressly return nil to exit the original execution (it continues in
  ;; the core.async threads).
  nil)

(defn- next-interceptor
  "Gets the next interceptor from the queue, or returns nil if the queue is exhausted."
  [context queue-index]
  (let [queue (::queue context)]
    (when (and (seq queue)
               (< queue-index (count queue)))
      (nth queue queue-index))))

(defn- invoke-interceptors
  "Invokes interceptors in the queue."
  [context]
  ;; The stage will be either :enter or :leave (errors are handled specially)
  (let [{::keys [stage execute-single? continuation execution-id]
         entry-bindings :bindings} context
        enter? (= :enter stage)]
    (log/debug :in 'invoke-interceptors
               :handling stage
               :execution-id execution-id)
    (loop [{::keys [resolving-error? error]
            :as loop-context} (dissoc context ::continuation)
           queue-index (:queue-index continuation 0)
           stack (:stack continuation)]
      (log/trace :in 'invoke-interceptors
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

        ;;; if got an ::error on :enter stage, then swap to leave stage.
        ;(and error
        ;     (or execute-single? (= :enter stage))
        ;
        ;     )
        ;;; Note: can either get an ::error, or a non-failed interceptor may change :bindings,
        ;;; but not both, so it's safe to directly recurse here, rather than pop up and rebind.
        ;(invoke-interceptors (enter-leave-stage loop-context stack))

        ;; When an error first occurs, switch resolving-error? on, which means we're attempting
        ;; to work back in the stack to find an interceptor that can handle the error.
        (and error (not resolving-error?))
        (invoke-interceptors (-> loop-context
                                 (enter-leave-stage stack)
                                 (assoc ::resolving-error? true)))

        ;; When executing a single stage (:error or :leave), after resolving an error
        ;; either now complete, or just clear the resolving error flag.
        ;; NOTE: could make the resolving-error? flag more recur state (with queue-index and stack)
        ;; but that the additional complication is not worth it for what should be an exceptional case.
        (and resolving-error?
             (not error))
        (if execute-single?
          loop-context
          (recur (dissoc loop-context ::resolving-error?) queue-index stack))

        :else
        (do
          (let [initial-context (check-terminators loop-context)
                interceptor (next-interceptor initial-context queue-index)]
            (if-not interceptor
              ;; Terminate when the queue is empty (possibly because a terminator did its job
              ;; and emptied it).
              (case stage
                :enter (if execute-single?
                         ;; After execute-single w/ :enter, leave the context setup to
                         ;; call execute-single w/ :leave.
                         (flip-stack-into-queue initial-context stack)
                         ;; But normally, at the end of :enter stage, flip over to
                         ;; the :leave stage.
                         (invoke-interceptors (enter-leave-stage initial-context stack)))
                :leave initial-context)
              (let [queue-index' (inc queue-index)
                    stack' (when (or execute-single? enter?)
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
                  (recur result queue-index' stack'))))))))))

#_(defn- process-all
    [context interceptor-key]
    ;; If we're processing leave handlers, reverse the queue
    (let [context (if (= interceptor-key :leave) (update context ::queue reverse) context)
          context (with-bindings (or (:bindings context)
                                     {})
                    (process-all-with-binding context interceptor-key))]
      (if (::rebind context)
        (recur (dissoc context ::rebind) interceptor-key)
        context)))

(defn- invoke-interceptors-binder
  "Exists to support :bindings in the context; a wrapper around invoke-interceptors that
   handles a special case where invoke-interceptors returns early so that bindings can
   be re-bound."
  [context]
  (let [bindings (:bindings context)
        context' (if bindings
                   (with-bindings bindings
                     ;; Advance the execution until complete, until an exit to swap the bindings,
                     ;; or until the execution switches to async mode.
                     (invoke-interceptors context))
                   (invoke-interceptors context))
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

#_(defn- process-any-errors-with-binding
    "Unwinds the context by invoking :error functions of Interceptors on
    the ::stack of context, but **only** if there is an ::error present in the context."
    [context]
    (log/debug :in 'process-any-errors :execution-id (::execution-id context))
    (loop [context context]
      (let [stack (::stack context)
            execution-id (::execution-id context)]
        (log/trace :context context)
        (if (empty? stack)
          context
          (let [interceptor (peek stack)
                pre-bindings (:bindings context)
                old-context context
                context (assoc context ::stack (pop stack))
                context (if (::error context)
                          (try-error context interceptor)
                          context)]
            (cond
              (channel? context) (go-async old-context context)
              (not= (:bindings context) pre-bindings) (assoc context ::rebind true)
              true (recur context)))))))

#_(defn- process-any-errors
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

#_(defn- enter-all
    "Establish the bindings present in `context` as thread local
    bindings, and then invoke enter-all-with-binding. Conditionally
    re-establish bindings if a change in bindings is made by an
    interceptor."
    [context]
    (process-all context :enter))

#_(defn- leave-all-with-binding
    "Unwinds the context by invoking :leave functions of Interceptors on
    the ::stack of context. Returns updated context."
    [context]
    (log/debug :in 'leave-all :execution-id (::execution-id context))
    (loop [context context]
      (let [stack (::stack context)]
        (log/trace :context context)
        (if (empty? stack)
          context
          (let [interceptor (peek stack)
                pre-bindings (:bindings context)
                old-context context
                context (assoc context ::stack (pop stack))
                context (if (::error context)
                          (try-error context interceptor)
                          (try-f context interceptor :leave))]
            (cond
              (channel? context) (go-async old-context context)
              (not= (:bindings context) pre-bindings) (assoc context ::rebind true)
              true (recur context)))))))

#_(defn- leave-all
    "Establish the bindings present in `context` as thread local
    bindings, and then invoke leave-all-with-binding. Conditionally
    re-establish bindings if a change in bindings is made by an
    interceptor."
    [context]
    (let [context (with-bindings (or (:bindings context)
                                     {})
                    (leave-all-with-binding context))]
      (if (::rebind context)
        (recur (dissoc context ::rebind))
        context)))

(defn enqueue
  "Adds interceptors to the end of context's execution queue. Creates
  the queue if necessary. Returns the updated context."
  [context interceptors]
  {:pre [(every? interceptor/interceptor? interceptors)]}
  (log/trace :enqueue (map name interceptors) :context context)
  (update context ::queue
          #(into (or % []) interceptors)))

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
  functions and begins executing the :leave functions."
  [context]
  (log/trace :in 'terminate :context context)
  (dissoc context ::queue))

(defn terminate-when
  "Adds pred as a terminating condition of the context. pred is a
  function that takes a context as its argument. It will be invoked
  after every Interceptor's :enter function. If pred returns logical
  true, execution will stop at that Interceptor."
  [context pred]
  (update-in context [::terminators] conj pred))

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
  ---
  Executes a queue of Interceptors attached to the context. Context
  must be a map, Interceptors are added with 'enqueue'.

  An Interceptor Record has keys :enter, :leave, and :error.
  The value of each key is a function; missing
  keys or nil values are ignored. When executing a context, all
  the `interceptor-key` functions are invoked in order.


  This function is deprecated in Pedestal 0.7.0, as there is no reason to use
  it."
  {:deprecated "0.7.0"}
  ([context interceptor-key]
   (some-> context
           begin
           (assoc ::stage interceptor-key
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
           (assoc ::stage :enter)
           invoke-interceptors-binder))
  ([context interceptors]
   (execute (enqueue context interceptors))))
