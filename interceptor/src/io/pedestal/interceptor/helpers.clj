; Copyright 2013 Relevance, Inc.
; Copyright 2014-2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor.helpers
  "A collection of interceptor helpers.

  The usage of the Interceptor API is preferred over the macros
  defined in this namespace. Usage of the macro helpers should be
  limited to cases where you are porting an existing Pedestal code
  base.
   
  The helper macros predate the interceptor API and can break AOT
  compilation but they are maintained for backwards
  compatibility. Refer to
  https://github.com/pedestal/pedestal/issues/308 and
  https://github.com/pedestal/pedestal/pull/301 for more details about
  macro helper issues and the rationale for the Interceptor API."
  (:require [io.pedestal.interceptor :as interceptor :refer [interceptor
                                                             interceptor-name]]))

(defmacro definterceptor
  "Define an instance of an interceptor and store it in a var. An
  optional doc string can be provided.
  The body can be anything that satisfies the IntoInterceptor protocol.

  usage:
    (definterceptor encode-response
       \"An interceptor that encodes the response as json\"
       (on-response encode-json))

  Alternatively, you may also:
    (def encode-response
      \"An interceptor that encodes the response as json\"
      (on-response encode-json)"
  [name & body]
  (let [init (if (string? (first body))
               (second body)
               (first body))
        doc (when (string? (first body))
              (first body))]
    `(def ~(with-meta name {:doc doc})  ~(interceptor init))))

(defn- infer-basic-interceptor-function
  "Given list `args`, infer a form that will evaluate to a function
  from the list. The passed list will be interpreted as entirely
  containing information for specifying one function, and no
  additional information."
  [args]
  (cond
   (symbol? (first args)) (first args)
   (and (vector? (first args))
        (< 1 (count args))) `(fn ~@args)))

(defn- infer-first-interceptor-function
  "Given list `args`, infer a form that will evaluate to a function
  from the list. The passed list will be interpreted as potentially
  containing information for specifying multiple functions, and only
  information for the first function will be inferred from the list."
  [args]
  (cond
   (symbol? (first args)) (first args)
   (list? (first args)) (conj (first args) 'fn)))

(defn infer-rest-interceptor-function
  "Given list `args`, return the rest of args that would remain after
  removing the elements of args that specify the form returned by
  infer-first-interceptor-function."
  [args]
  ;; Right now there's no ambiguity, so just use rest.
  (rest args))

(defmacro ^{:private true} defsimpleinterceptordef
  "Defines a macro which is used for defining interceptor defs. `n`
  is the name of an interceptorfn which adapts a fn to the interceptor
  framework, e.g. before, `docstring` is the docstring for the macro in question."
  [n docstring]
  `(defmacro ~(symbol (str "def" (name n)))
     ~docstring
     [macro-name# & args#]
     (let [[docstring# args#] (if (string? (first args#))
                                [(first args#) (rest args#)]
                                [nil args#])
           prefix-list# (if docstring#
                          (list macro-name# docstring#)
                          (list macro-name#))
           fn-form# (infer-basic-interceptor-function args#)
           interceptor-name# (keyword (name (ns-name *ns*)) (name macro-name#))]
       `(definterceptor ~@prefix-list#
          (~~n ~interceptor-name# ~fn-form#)))))

(defn before
  "Returns an interceptor which calls `f` on context during the enter
  stage."
  ([f] (interceptor {:enter f}))
  ([f & args]
     (let [[n f args] (if (fn? f)
                        [nil f args]
                        [f (first args) (rest args)])]
       (interceptor {:name (interceptor-name n)
                     :enter #(apply f % args)}))))

(defsimpleinterceptordef before "Defines a before interceptor. The
  defined function performs processing during interceptor execution
  during the enter stage. The implicitly created function will operate
  on context, and return a value used as the new context, e.g.:

  (defbefore flag-zotted
    [context]
    (assoc context :zotted true))")

(defn after
  "Return an interceptor which calls `f` on context during the leave
  stage."
  ([f] (interceptor {:leave f}))
  ([f & args]
     (let [[n f args] (if (fn? f)
                        [nil f args]
                        [f (first args) (rest args)])]
       (interceptor {:name (interceptor-name n)
                     :leave #(apply f % args)}))))

(defsimpleinterceptordef after
  "Defines an after interceptor. The defined function is processed
  during the leave stage of interceptor execution. The implicitly
  created function will operate on context, and return a value used as
  the new context, e.g.:

  (defafter check-zotted
    [context]
    (if-not (:zotted context)
      (throw (ex-info \"Context was not zotted!\"
                      {:context context}))
      context))")

(defn around
  "Return an interceptor which calls `f1` on context during the enter
  stage, and calls `f2` on context during the leave stage."
  ([f1 f2]
     (interceptor {:enter f1
                   :leave f2}))
  ([n f1 f2]
     (interceptor {:name (interceptor-name n)
                   :enter f1
                   :leave f2})))

(defmacro defaround
  "Defines an around interceptor. The definition resembles a multiple
  arity function definition, however both fns are 1-arity. The first
  fn will be called during the enter stage, the second during the
  leave stage, e.g.:

  (defaround aroundinterceptor
    ([context] (assoc context :around :entering))
    ([context] (assoc context :around :leaving)))"

  [n & args]
  (let [[docstring args] (if (string? (first args))
                           [(first args) (rest args)]
                           [nil args])
        prefix-list (if docstring
                      (list n docstring)
                      (list n))
        [enter-fn-form args] [(infer-first-interceptor-function args)
                              (infer-rest-interceptor-function args)]
        [leave-fn-form args] [(infer-first-interceptor-function args)
                              (infer-rest-interceptor-function args)]
        interceptor-name# (keyword (name (ns-name *ns*)) (name n))]
    `(definterceptor ~@prefix-list
       (around ~interceptor-name# ~enter-fn-form ~leave-fn-form))))

(defn on-request
  "Returns an interceptor which updates the :request value of context
  with f during the enter stage."
  ([f] (before (fn [context]
                 (assoc context :request (f (:request context))))))
  ([f & args]
     (let [[n f args] (if (fn? f)
                        [nil f args]
                        [f (first args) (rest args)])]
       (interceptor {:name (interceptor-name n)
                     :enter (fn [context]
                              (assoc context :request (apply f (:request context) args)))}))))

(defsimpleinterceptordef on-request
  "Defines an on-request interceptor. The definition performs
  pre-processing on a request during the enter stage of interceptor
  execution. The implicitly created interceptor will extract the
  request from the context it receives, pass it to the defined
  function, and then associate the return value from the defined
  function as into context with the :request key and return
  context, e.g.:

  (defon-request parse-body-as-wibblefish
    [request]
    (assoc request :wibblefish-params
           (wibblefish-parse (:body request))))

  This is equivalent to:

  (defbefore parse-body-as-wibblefish
    [context]
    (let [request (:request context)
          new-request (assoc request :wibblefish-params
                             (wibblefish-parse (:body request)))]
      (assoc context :request new-request)))")

(defn on-response
  "Returns an interceptor which updates the :response value of context
  with f during the leave stage."
  ([f] (after (fn [context]
                (assoc context :response (f (:response context))))))
  ([f & args]
     (let [[n f args] (if (fn? f)
                        [nil f args]
                        [f (first args) (rest args)])]
       (interceptor {:name (interceptor-name n)
                     :leave (fn [context]
                              (assoc context :response (apply f (:response context) args)))}))))

(defsimpleinterceptordef on-response
  "Defines an on-response interceptor. The definition performs post
  processing on a response during the leave stage of interceptor
  execution. The implicitly created interceptor will extract the
  response from the context it receives, pass it to the defined
  function, and then associate the return value from the defined function
  into context with the :response key and return context, e.g.:

  (defon-response change-body-to-html
    [response]
    (assoc response :body
           (render-to-html (:body response))))

  This is equivalent to:

  (defafter change-body-to-html
    [context]
    (let [response (:response context)
          new-response (assoc response :body
                              (render-to-html (:body response)))]
      (assoc context :response new-response)))")

(defn handler
  "Returns an interceptor which calls f on the :request value of
  context, and assoc's the return value as :response into context during the
  enter stage."
  ([f]
     (before (fn [context]
               (assoc context :response (f (:request context))))))
  ([n f]
     (before (interceptor-name n)
             (fn [context]
               (assoc context :response (f (:request context)))))))

(defsimpleinterceptordef handler
  "Defines a handler interceptor. The definition mirrors a ring-style
  request handler and is made in terms of a ring style request. The
  implicitly created interceptor will extract the request from the
  context it receives, pass it to the defined function, and then
  associate the return value from the defined function as into
  context with the :response key and return context, e.g.:

  (defhandler hello-name
    [request]
    (ring.util.response/response
      (str \"Hello, \" (-> request
                           :params
                           :name))))

  This is equivalent to:

  (defbefore hello-name
    [context]
    (let [request (:request context)
          response (ring.util.response/response
                     (str \"Hello, \" (-> request
                                          :params
                                          :name)))]
      (assoc context :response response)))")

(defn middleware
  "Returns an interceptor which calls `f1` on the :request value of
  context during the enter stage, and `f2` on the :response value of
  context during the leave stage."
  ([f1 f2]
     (interceptor {:enter (when f1 #(update-in % [:request] f1))
                   :leave (when f2 #(update-in % [:response] f2))}))
  ([n f1 f2]
     (interceptor {:name (interceptor-name n)
                   :enter (when f1 #(update-in % [:request] f1))
                   :leave (when f2 #(update-in % [:response] f2))})))

(defmacro defmiddleware
  "Defines a middleware interceptor. The definition resembles a
  multiple arity function definition, however both fns are
  1-arity. The first fn will be called during the enter stage with the
  value of the :request key in the context, the second during the
  leave stage with the response key in the context, e.g.:

  (defmiddleware middleware-interceptor
    ([request] (assoc request :middleware :on-request))
    ([response] (assoc response :middleware :on-response)))"

  [n & args]
  (let [[docstring args] (if (string? (first args))
                           [(first args) (rest args)]
                           [nil args])
        prefix-list (if docstring
                      (list n docstring)
                      (list n))
        [enter-fn-form args] [(infer-first-interceptor-function args)
                              (infer-rest-interceptor-function args)]
        [leave-fn-form args] [(infer-first-interceptor-function args)
                              (infer-rest-interceptor-function args)]
        interceptor-name# (keyword (name (ns-name *ns*)) (name n))]
    `(definterceptor ~@prefix-list
       (middleware ~interceptor-name# ~enter-fn-form ~leave-fn-form))))

