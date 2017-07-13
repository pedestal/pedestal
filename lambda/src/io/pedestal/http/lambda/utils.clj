(ns io.pedestal.http.lambda.utils
  (:require [clojure.string :as string])
  (:import (java.io InputStream
                    OutputStream)
           (com.amazonaws.services.lambda.runtime Context
                                                  RequestHandler
                                                  RequestStreamHandler)))

(defprotocol IntoRequestStreamHandler
  (-request-stream-handler [t]))

(extend-protocol IntoRequestStreamHandler

  RequestStreamHandler
  (-request-stream-handler [t] t)

  clojure.lang.Fn
  (-request-stream-handler [t]
    (reify RequestStreamHandler
      (handleRequest [this instream outstream context]
        (t ^InputStream instream ^Outputstream outstream ^Context context)))))

(defprotocol IntoRequestHandler
  (-request-handler [t]))

(extend-protocol IntoRequestHandler

  RequestHandler
  (-request-handler [t] t)

  clojure.lang.Fn
  (-request-handler [t]
    (reify RequestHandler
      (handleRequest [this input context]
        (t input ^Context context)))))


(defn request-stream-handler
  "Given a value, produces and returns a RequestStreamHandler **object**.

  If the value is a function, that fn must take three arguments (InputStream, OutputStream, runtime.Context).

  Note: This function is only intended for testing or composing existing handlers.
  It can handle RequestStreamHandler classes, objects, and anything
  that satisfies the IntoRequestStreamHandler protocol"
  [t]
  {:pre [(if-not (or (and (class? t)
                          ((supers t) com.amazonaws.services.lambda.runtime.RequestStreamHandler))
                     (satisfies? IntoRequestStreamHandler t))
           (throw (ex-info "You're trying to use something as a RequestStreamHandler that isn't supported by the conversion protocol; Perhaps you need to extend it?"
                           {:t t
                            :type (type t)}))
           true)]
   :post [(instance? RequestStreamHandler %)]}
  (if (and (class? t)
           ((supers t) com.amazonaws.services.lambda.runtime.RequestStreamHandler))
    (eval `(new ~t))
    (-request-stream-handler t)))

(defn request-handler
  "Given a value, produces and returns a RequestHandler **object**.

  If the value is a function, that fn must take two arguments (an input Object and runtime.Context).

  Note: This function is only intended for testing or composing existing handlers.
  It can handle RequestHandler classes, objects, and anything
  that satisfies the IntoRequestHandler protocol"
  [t]
  {:pre [(if-not (or (and (class? t)
                          ((supers t) com.amazonaws.services.lambda.runtime.RequestHandler))
                     (satisfies? IntoRequestHandler t))
           (throw (ex-info "You're trying to use something as a RequestHandler that isn't supported by the conversion protocol; Perhaps you need to extend it?"
                           {:t t
                            :type (type t)}))
           true)]
   :post [(instance? RequestHandler %)]}
  (if (and (class? t)
           ((supers t) com.amazonaws.services.lambda.runtime.RequestHandler))
    (eval `(new ~t))
    (-request-handler t)))

(defn lambda-name
  "A utility function for converting/producing Lambda symbol names"
  [x]
  (cond
    (symbol? x) x
    (string? x) (symbol x)
    (keyword? x) (symbol (name x))))

;;TODO: Consider preserving metadata with these macros

(defmacro gen-stream-lambda
  "Given a symbol (class name) and a function of four args (this, InputStream, OutputStream, runtime.Context),
  Generate a named class that can be invoked as an AWS Lambda Function implementing RequestStreamHandler

  If the classname is not fully packaged qualified, the current namespace is used as the package name"
  [lambda-class-name request-stream-handler-fn]
  (let [package-qualified-classname (if (string/includes? (str lambda-class-name) ".")
                                      lambda-class-name
                                      (symbol (str *ns* "." lambda-class-name)))
        prefix (gensym "lambda")
        handler-request-sym (symbol (str prefix "handleRequest"))]
    `(do (gen-class {:name ~package-qualified-classname
                     :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler]
                     :prefix ~prefix})
         (def ~handler-request-sym ~request-stream-handler-fn))))

;; For example...
;; (gen-stream-lambda MyLambdaName f)
;; ... or ...
;; (gen-stream-lambda AnotherLambda (fn [this input-stream output-stream ctx] ctx))

(defmacro gen-lambda
  "Given a symbol (class name) and a function of three args (this, input Object, runtime.Context),
  Generate a named class that can be invoked as an AWS Lambda Function implementing RequestHandler

  If the classname is not fully packaged qualified, the current namespace is used as the package name"
  [lambda-class-name request-handler-fn]
  (let [package-qualified-classname (if (string/includes? (str lambda-class-name) ".")
                                      lambda-class-name
                                      (symbol (str *ns* "." lambda-class-name)))
        prefix (gensym "lambda")
        handler-request-sym (symbol (str prefix "handleRequest"))]
    `(do (gen-class {:name ~package-qualified-classname
                     :implements [com.amazonaws.services.lambda.runtime.RequestHandler]
                     :prefix ~prefix})
         (def ~handler-request-sym ~request-handler-fn))))

;; For example...
;; (gen-lambda MyLambdaName f)
;; ... or ...
;; (gen-lambda AnotherLambda (fn [this input ctx] ctx))

;;TODO Handle `docstring`
(defmacro deflambda
  "Creates a names class that can be invoked as an AWS Lambda Function.
  If you define a two-arity lambda (input Object, runtime Context),
  a RequestHandler is produced.
  If you define a three-arity lambda (InputStream, OutputStream, runtime Context),
  a RequestStreamHandler is produced.

  See also: gen-stream-lambda and gen-lambda"
  [name args & body]
  (let [[docstring & body] (if (string? (first body)) body (cons nil body))]
    (case (count args)
      2 `(gen-lambda ~name (fn ~(into ['this] args) ~@body))
      3 `(gen-stream-lambda ~name (fn ~(into ['this] args) ~@body))
      (throw (ex-info (str "In Lambda: " name  " - Lambdas created with 'deflambda' must be 2 or 3 arguments. Found: " args)
                      {:lamdba name
                       :args args})))))

;; All Lambda proxy requests through API Gateway (in proxy mode) are packaged
;; up into a single JSON object.  Those details can be found here:
;; https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-set-up-simple-proxy.html#api-gateway-simple-proxy-for-lambda-input-format

(defn apigw-request-map
  "Given a parsed JSON event from API Gateway,
  return a Ring compatible request map.

  This assumes the apigw event has strings as keys
  -- no conversion has taken place on the JSON object other than the original parse.
  -- This ensures parse optimizations can be made without affecting downstream code."
  [apigw-event]
  (let [[http-version host] (string/split (get headers "Via" "") #" ")
        path (get apigw-event "path" "/")
        headers (get apigw-event "headers" {})
        port (try (Integer/parseInt (get headers "X-Forwarded-Port" "")) (catch Throwable t 80))
        source-ip (get-in apigw-event ["requestContext" "identity" "sourceIp"] "")]
    {:server-port server-port
     :server-name (or host "")
     :remote-addr source-ip
     :uri path
     ;:query-string query-string
     :query-string-params (get apigw-event "queryStringParameters")
     :scheme (get headers "X-Forwarded-Proto" "http")
     :request-method (some-> (get apigw-event "httpMethod")
                             string/lower-case
                             keyword)
     :headers headers
     ;:ssl-client-cert ssl-client-cert
     :body (get apigw-event "body")
     :path-info path
     :protocol (str "HTTP/" (or http-version "1.1"))
     :async-supported? false}))

;; TODO: Create Chain Provider for running an interceptor chain directly in a lambda
;; TODO: Create a function that given a service map, performs a gen-class pushing out the Lambda

;; TODO: Create classes as needed for Lambda Servlet handling

