; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.impl.interceptor
  "Interceptor pattern. Executes a chain of Interceptor functions on a
  common \"context\" map, maintaining a virtual \"stack\", with error
  handling and support for asynchronous execution."
  (:refer-clojure :exclude (name))
  (:require [io.pedestal.service.log :as log])
  (:import java.util.concurrent.atomic.AtomicLong))

(defn- name [interceptor]
  (get interceptor :name (pr-str interceptor)))

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
             (assoc context ::error t)))
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
      (let [ex (::error context)]
        (log/debug :interceptor (name interceptor)
                   :stage :error
                   :execution-id execution-id)
        (try (error-fn (dissoc context ::error) ex)
             (catch Throwable t
               (if (identical? t ex)
                 (do (log/debug :rethrow t :execution-id execution-id)
                     context)
                 (do (log/debug :throw t :suppressed ex :execution-id execution-id)
                     (-> context
                         (assoc ::error t)
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

(defn- go-async
  "Call all :enter-async functions, passing context."
  [{:keys [enter-async] :as context}]
  (doseq [enter-async-fn enter-async]
    (enter-async-fn context)))

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
           (nil? context) (go-async old-context)
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
              context (assoc context ::stack (pop stack))
              context (if (::error context)
                        (try-error context interceptor)
                        (try-f context interceptor :leave))]
          (if (not= (:bindings context) pre-bindings)
            (assoc context ::rebind true)
            (recur context)))))))

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

(defn- invoke-all-with-binding
  "Used by 'pause' and 'resume'. Gets the stack at stack-key from the
  context, invokes function found at stage of each interceptor on the
  stack. Returns updated context. Unlike 'enter-all' and 'leave-all',
  does not catch exceptions. During a 'pause', exceptions should be
  handled by the normal mechanisms in enter-all/leave-all. During a
  'resume', exceptions will propagate out to the calling code."
  [context stack-key stage]
  (loop [context context]
    (let [stack (get context stack-key)]
      (if (empty? stack)
        context
        (let [interceptor (peek stack)
              pre-binding (:bindings context)
              execution-id (::execution-id context)
              f (get interceptor stage)
              skipped (nil? f)
              context (-> context
                          (assoc stack-key (pop stack))
                          ((or f identity) ))]
          (log/debug :interceptor (name interceptor)
                     :stage stage
                     :skipped? skipped
                     :execution-id execution-id
                     :fn f)
          (if (not= (:bindings context) pre-binding)
            (assoc context ::rebind true)
            (recur context)))))))

(defn- invoke-all
  "Establish the bindings present in `context` as thread local
  bindings, and then invoke invoke-all-with-binding. Conditionally
  re-establish bindings if a change in bindings is made by an
  interceptor."
  [context stack-key stage]
  (let [context (with-bindings (or (:bindings context)
                                   {})
                  (invoke-all-with-binding context stack-key stage))]
    (if (::rebind context)
      (recur (dissoc context ::rebind) stack-key stage)
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

(defrecord Interceptor [name enter leave error pause resume])

(defn interceptor
  "Treats arguments as a map from keys to functions and
  constructs an Interceptor from that map.

  Keys should be one of: :name :enter :leave :error :pause :resume"
  ([& more]
     (map->Interceptor (apply array-map more))))

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
  :leave, :error, :pause, and :resume. The value of each key is a
  function; missing keys or nil values are ignored. When executing a
  context, first all the :enter functions are invoked in order. As
  this happens, the Interceptors are pushed on to a stack.

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
  being handled, execute will throw it.

  The :pause and :resume functions are used in asynchronous contexts.
  Any :enter or :leave function may call 'pause' to suspend the
  current execution, after which it MUST return nil. See also
  'with-pause'. Before returning, the function can dispatch operation
  to another thread or schedule it for later execution. When it is
  time to continue execution, another thread should call 'resume'.

  When 'pause' is called, the :pause functions of any Interceptors
  currently on the stack will be invoked. These functions may be used,
  for example, to release resources that will not be needed while
  waiting for some external event. When 'resume' is called, the
  :resume functions are invoked in the opposite order."
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

(defn pause
  "Prepares this context to leave its current thread (and complete
  asynchronously on another thread) by invoking the :pause functions
  of any Interceptors on the stack. Returns an updated context."
  [context]
  (log/debug :in 'pause :execution-id (::execution-id context))
  (log/trace :context context)
  (-> context
      (assoc ::pause-stack (::stack context))
      (invoke-all ::pause-stack :pause)
      (dissoc ::pause-stack)))

(defn resume
  "Prepares this context to resume operation on the current thread by
  invoking the :resume functions of any Interceptors on the stack, in
  reverse order. Returns an updated context."
  [context]
  (log/debug :in 'resume :execution-id (::execution-id context))
  (log/trace :context context)
  (-> context
      (assoc ::resume-stack (reverse (::stack context)))
      (invoke-all ::resume-stack :resume)
      (dissoc ::resume-stack)
      execute))

(defmacro with-pause
  "Bindings is a vector of [binding-form expr] where expr returns a
  context map. Invokes 'pause' on the context returned by expr, binds
  it to 'binding-form', and evaluates body. Returns nil. It is assumed
  that body will dispatch to another thread which eventually calls
  'resume'."
  [bindings & body]
  {:pre [(vector? bindings)
         (= 2 (count bindings))]}
  `(let [~(first bindings) (pause ~(second bindings))]
     ~@body
     nil))

