; Copyright 2013 Relevance, Inc.
; Copyright 2014-2019 Cognitect, Inc.

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
  (:import java.util.concurrent.atomic.AtomicLong))

(declare execute)
(declare execute-only)

(defn- channel? [c] (instance? clojure.core.async.impl.protocols.Channel c))

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
  "If error-fn is not nil, invokes it on context and the current ::error
  from context."
  [context interceptor]
  (let [execution-id (::execution-id context)]
    (if-let [error-fn (get interceptor :error)]
      (let [ex (::error context)
            stage :error]
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
                         (update-in [::suppressed] conj ex)))))))
      (do (log/trace :interceptor (name interceptor)
                     :skipped? true
                     :stage :error
                     :execution-id execution-id)
          context))))

(defn- check-terminators
  "Invokes each predicate in ::terminators on context. If any predicate
  returns true, removes ::queue from context."
  [context]
  (if (some #(% context) (::terminators context))
    (let [execution-id (::execution-id context)]
      (log/debug :in 'check-terminators
                 :terminate? true
                 :execution-id execution-id)
      (dissoc context ::queue))
    context))

(defn- prepare-for-async
  "Call all of the :enter-async functions in a context. The purpose of these
  functions is to ready backing servlets or any other machinery for preparing
  an asynchronous response."
  [{:keys [enter-async] :as context}]
  (doseq [enter-async-fn enter-async]
    (enter-async-fn context)))

(defn- go-async
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

(defn- process-all-with-binding
  "Invokes `interceptor-key` functions of all Interceptors on the execution
  ::queue of context, saves them on the ::stack of context.
  Returns updated context.
  By default, `interceptor-key` is :enter"
  ([context]
   (process-all-with-binding context :enter))
  ([context interceptor-key]
  (log/debug :in 'process-all :handling interceptor-key :execution-id (::execution-id context))
   (loop [context (check-terminators context)]
    (let [queue (::queue context)
          stack (::stack context)
          execution-id (::execution-id context)]
      (log/trace :context context)
      (if (empty? queue)
        context
        (let [interceptor (peek queue)
              pre-bindings (:bindings context)
              old-context context
              new-queue (pop queue)
              ;; conj on nil returns a list, acts like a stack:
              new-stack (conj stack interceptor)
              context (-> context
                          (assoc ::queue new-queue
                                 ::stack new-stack)
                          (try-f interceptor interceptor-key))]
          (cond
            (channel? context) (go-async (assoc old-context
                                                ::async-info {:interceptor interceptor
                                                              :stage interceptor-key
                                                              :stack new-stack})
                                         context)
            (::error context) (dissoc context ::queue)
            (not= (:bindings context) pre-bindings) (assoc context ::rebind true)
            true (recur (check-terminators context)))))))))

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
  [context]
  (log/debug :in 'leave-all :execution-id (::execution-id context))
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
                        (try-f context interceptor :leave))]
          (cond
           (channel? context) (go-async old-context context)
           (not= (:bindings context) pre-bindings) (assoc context ::rebind true)
           true (recur context)))))))

(defn- leave-all
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
  the queue if necessary. Returns updated context."
  [context interceptors]
  {:pre [(every? interceptor/interceptor? interceptors)]}
  (log/trace :enqueue (map name interceptors) :context context)
  (update-in context [::queue]
             (fnil into clojure.lang.PersistentQueue/EMPTY)
             interceptors))

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
  the `interceptor-key` functions are invoked in order. As this happens, the
  Interceptors are pushed on to a stack."
  ([context interceptor-key]
   (let [context (some-> context
                         begin
                         (process-all interceptor-key)
                         terminate
                         process-any-errors
                         end)]
     (if-let [ex (::error context)]
       (throw ex)
       context)))
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

  When execution reaches the end of the queue, it begins popping
  Interceptors off the stack and calling their :leave functions.
  Therefore :leave functions are called in the opposite order from
  :enter functions.

  Both the :enter and :leave functions are called on a single
  argument, the context map, and return an updated context.

  If any Interceptor function throws an exception, execution stops and
  begins popping Interceptors off the stack and calling their :error
  functions. The :error function takes two arguments: the context and
  an exception. It may either handle the exception, in which case the
  execution continues with the next :leave function on the stack; or
  re-throw the exception, passing control to the :error function on
  the stack. If the exception reaches the end of the stack without
  being handled, execute will throw it."
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
