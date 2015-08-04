; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.impl.interceptor
  "Interceptor pattern. Executes a chain of Interceptor functions on a
  common \"context\" map, maintaining a virtual \"stack\", with error
  handling and support for asynchronous execution."
  (:refer-clojure :exclude (name))
  (:require [clojure.core.async :as async :refer [<! go]]
            [io.pedestal.log :as log])
  (:import java.util.concurrent.atomic.AtomicLong))

(declare execute)

(defn- channel? [c] (instance? clojure.core.async.impl.protocols.Channel c))

(defn- name [interceptor]
  (get interceptor :name (pr-str interceptor)))

;; TODO: liter this through the call sites below.  This will allow pattern match on the results
(defn- throwable->ex-info [^Throwable t execution-id interceptor stage]
  (ex-info (str "Interceptor Exception: " (.getMessage t))
           (merge {:execution-id execution-id
                   :stage stage
                   :interceptor (name interceptor)
                   :exception-type (keyword (pr-str (type t)))
                   :exception t}
                  (ex-data t))
           t))

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
               (if (identical? (type t) (type (:exception ex)))
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
  [old-context context-channel]
  (prepare-for-async old-context)
  (go
   (let [new-context (<! context-channel)]
      (execute new-context)))
  nil)

(defn- enter-all-with-binding
  "Invokes :enter functions of all Interceptors on the execution
  ::queue of context, saves them on the ::stack of context. Returns
  updated context."
  [context]
  (log/debug :in 'enter-all :execution-id (::execution-id context))
  (loop [context context]
    (let [queue (::queue context)
          stack (::stack context)
          execution-id (::execution-id context)]
      (log/trace :context context)
      (if (empty? queue)
        context
        (let [interceptor (peek queue)
              pre-bindings (:bindings context)
              old-context context
              context (-> context
                          (assoc ::queue (pop queue))
                          ;; conj on nil returns a list, acts like a stack:
                          (assoc ::stack (conj stack interceptor))
                          (try-f interceptor :enter))]
          (cond
            (channel? context) (go-async old-context context)
            (::error context) (dissoc context ::queue)
            (not= (:bindings context) pre-bindings) (assoc context ::rebind true)
            true (recur (check-terminators context))))))))

(defn- enter-all
  "Establish the bindings present in `context` as thread local
  bindings, and then invoke enter-all-with-binding. Conditionally
  re-establish bindings if a change in bindings is made by an
  interceptor."
  [context]
  (let [context (with-bindings (or (:bindings context)
                                   {})
                  (enter-all-with-binding context))]
    (if (::rebind context)
      (recur (dissoc context ::rebind))
      context)))

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
  [context & interceptors]
  (log/trace :enqueue (map name interceptors) :context context)
  (update-in context [::queue]
             (fnil into clojure.lang.PersistentQueue/EMPTY)
             interceptors))

(defn enqueue*
  "Like 'enqueue' but the last argument is a sequence of interceptors
  to add to the context's execution queue."
  [context & interceptors-and-seq]
  (apply enqueue context (apply list* interceptors-and-seq)))

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
  (log/debug :in 'end :execution-id execution-id)
  (log/trace :context context)
  context)

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
  [context]
  (let [context (some-> context
                        begin
                        enter-all
                        (dissoc ::queue)
                        leave-all
                        (dissoc ::stack ::execution-id)
                        end)]
    (if-let [ex (::error context)]
      (throw ex)
      context)))

