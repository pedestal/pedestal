(ns io.pedestal.http.aws.lambda.utils
  (:require [clojure.string :as string]
            [io.pedestal.interceptor.chain :as chain])
  (:import (java.io ;InputStream
                    ;OutputStream
                    ;InputStreamReader
                    ;PushbackReader
                    ByteArrayInputStream
                    ByteArrayOutputStream)
           (com.amazonaws.services.lambda.runtime Context
                                                  RequestHandler
                                                  RequestStreamHandler)))


(defn apigw-request-map
  "Given a parsed JSON event from API Gateway,
  return a Ring compatible request map.

  Optionally, you can decide to `process-headers?`, lower-casing them all to conform with the Ring spec
   defaults to `true`

  This assumes the apigw event has strings as keys
  -- no conversion has taken place on the JSON object other than the original parse.
  -- This ensures parse optimizations can be made without affecting downstream code."
  ([apigw-event]
   (apigw-request-map true))
  ([apigw-event process-headers?]
   (let [path (get apigw-event "path" "/")
         headers (get apigw-event "headers" {})
         [http-version host] (string/split (get headers "Via" "") #" ")
         port (try (Integer/parseInt (get headers "X-Forwarded-Port" "")) (catch Throwable t 80))
         source-ip (get-in apigw-event ["requestContext" "identity" "sourceIp"] "")]
     {:server-port port
      :server-name (or host "")
      :remote-addr source-ip
      :uri path
      ;:query-string query-string
      :query-string-params (get apigw-event "queryStringParameters")
      :path-params (get apigw-event "pathParameters" {})
      :scheme (get headers "X-Forwarded-Proto" "http")
      :request-method (some-> (get apigw-event "httpMethod")
                              string/lower-case
                              keyword)
      :headers (if process-headers?
                 (persistent! (reduce (fn [hs [k v]]
                                        (assoc! hs (string/lower-case k) v))
                                      (transient {})
                                      headers))
                 headers)
      ;:ssl-client-cert ssl-client-cert
      :body (when-let [body (get apigw-event "body")]
              (ByteArrayInputStream. (.getBytes ^String body "UTF-8")))
      :path-info path
      :protocol (str "HTTP/" (or http-version "1.1"))
      :async-supported? false})))

(defn resolve-body-processor []
  ;;TODO:
  identity)

(defn apigw-response
  ([ring-response]
   (apigw-response ring-response identity))
  ([ring-response body-process-fn]
   (let [{:keys [status body headers]} ring-response
        processed-body (body-process-fn body)
                       ;(if (string? body)
                       ;  body
                       ;  (->> (ByteArrayOutputStream.)
                       ;       (servlet-utils/write-body-to-stream body)
                       ;       (.toString)))
                       ]
    {"statusCode" (or status (if (string/blank? processed-body) 400 200))
     "body" processed-body
     "headers" headers})))

;; --- Proxy doesn't have to be InputStream/OutputStream!
;;     It will perform the JSON parse automatically ---
(defn direct-apigw-provider
  "Given a service map, return a service map with a provider function
  for an AWS API Gateway event, under `:io.pedestal.aws.lambda/apigw-handler`.

  This provider function takes the apigw-event map and the runtime.Context
  and returns an AWS API Gateway response map (containing -- :statusCode :body :headers)
  You may want to add a custom interceptor in your chain to handle Scheduled Events.

  This chain terminates if a Ring `:response` is found in the context
  or an API Gateway `:apigw-response` map is found.

  All additional conversion, coercion, writing, and extension should be handled by
  interceptors in the interceptor chain."
  [service-map]
  (let [interceptors (:io.pedestal.http/interceptors service-map [])
        default-context (get-in service-map [:io.pedestal.http/container-options :default-context] {})
        body-processor (get-in service-map
                               [:io.pedestal.http/container-options :body-processor]
                               (resolve-body-processor))]
    (assoc service-map
           :io.pedestal.aws.lambda/apigw-handler
           (fn [apigw-event ^Context context] ;[^InputStream input-stream ^OutputStream output-stream ^Context context]
             (let [;event (json/parse-stream
                   ;        (java.io.PushbackReader. (java.io.InputStreamReader. input-stream))
                   ;        nil
                   ;        nil)
                   request (apigw-request-map apigw-event)
                   initial-context (merge {;:aws.lambda/input-stream input-stream
                                           ;:aws.lambda/output-stream output-stream
                                           :aws.lambda/context context
                                           :aws.apigw/event apigw-event
                                           :request request
                                           ::chain/terminators [#(let [resp (:response %)]
                                                                   (and (map? resp)
                                                                        (integer? (:status resp))
                                                                        (map? (:headers resp))))
                                                                #(map? (:apigw-response %))]}
                                          default-context)
                   response-context (chain/execute initial-context interceptors)
                   response-map (or (:apigw-response response-context)
                                    ;; Use `or` to prevent evaluation
                                    (some-> (:response response-context)
                                            (apigw-response body-processor)))]
               response-map)))))


;(defprotocol IntoRequestStreamHandler
;  (-request-stream-handler [t]))
;
;(extend-protocol IntoRequestStreamHandler
;
;  RequestStreamHandler
;  (-request-stream-handler [t] t)
;
;  clojure.lang.Fn
;  (-request-stream-handler [t]
;    (reify RequestStreamHandler
;      (handleRequest [this instream outstream context]
;        (t ^InputStream instream ^Outputstream outstream ^Context context)))))
;
;(defprotocol IntoRequestHandler
;  (-request-handler [t]))
;
;(extend-protocol IntoRequestHandler
;
;  RequestHandler
;  (-request-handler [t] t)
;
;  clojure.lang.Fn
;  (-request-handler [t]
;    (reify RequestHandler
;      (handleRequest [this input context]
;        (t input ^Context context)))))
;
;
;(defn request-stream-handler
;  "Given a value, produces and returns a RequestStreamHandler **object**.
;
;  If the value is a function, that fn must take three arguments (InputStream, OutputStream, runtime.Context).
;
;  Note: This function is only intended for testing or composing existing handlers.
;  It can handle RequestStreamHandler classes, objects, and anything
;  that satisfies the IntoRequestStreamHandler protocol"
;  [t]
;  {:pre [(if-not (or (and (class? t)
;                          ((supers t) com.amazonaws.services.lambda.runtime.RequestStreamHandler))
;                     (satisfies? IntoRequestStreamHandler t))
;           (throw (ex-info "You're trying to use something as a RequestStreamHandler that isn't supported by the conversion protocol; Perhaps you need to extend it?"
;                           {:t t
;                            :type (type t)}))
;           true)]
;   :post [(instance? RequestStreamHandler %)]}
;  (if (and (class? t)
;           ((supers t) com.amazonaws.services.lambda.runtime.RequestStreamHandler))
;    (eval `(new ~t))
;    (-request-stream-handler t)))
;
;(defn request-handler
;  "Given a value, produces and returns a RequestHandler **object**.
;
;  If the value is a function, that fn must take two arguments (an input Object and runtime.Context).
;
;  Note: This function is only intended for testing or composing existing handlers.
;  It can handle RequestHandler classes, objects, and anything
;  that satisfies the IntoRequestHandler protocol"
;  [t]
;  {:pre [(if-not (or (and (class? t)
;                          ((supers t) com.amazonaws.services.lambda.runtime.RequestHandler))
;                     (satisfies? IntoRequestHandler t))
;           (throw (ex-info "You're trying to use something as a RequestHandler that isn't supported by the conversion protocol; Perhaps you need to extend it?"
;                           {:t t
;                            :type (type t)}))
;           true)]
;   :post [(instance? RequestHandler %)]}
;  (if (and (class? t)
;           ((supers t) com.amazonaws.services.lambda.runtime.RequestHandler))
;    (eval `(new ~t))
;    (-request-handler t)))
;
;(defn lambda-name
;  "A utility function for converting/producing Lambda symbol names"
;  [x]
;  (cond
;    (symbol? x) x
;    (string? x) (symbol x)
;    (keyword? x) (if-let [ns-str (namespace x)]
;                   (symbol (str ns-str "." (name x)))
;                   (symbol (name x)))))
;
;;;TODO: Consider preserving metadata with these macros
;
;(defmacro gen-stream-lambda
;  "Given a symbol (class name) and a function of four args (this, InputStream, OutputStream, runtime.Context),
;  Generate a named class that can be invoked as an AWS Lambda Function implementing RequestStreamHandler
;
;  If the classname is not fully packaged qualified, the current namespace is used as the package name"
;  [lambda-class-name request-stream-handler-fn]
;  (let [package-qualified-classname (if (string/includes? (str lambda-class-name) ".")
;                                      lambda-class-name
;                                      (symbol (str *ns* "." lambda-class-name)))
;        prefix (gensym "lambda")
;        handler-request-sym (symbol (str prefix "handleRequest"))]
;    `(do (gen-class {:name ~package-qualified-classname
;                     :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler]
;                     :prefix ~prefix})
;         (def ~handler-request-sym ~request-stream-handler-fn))))
;
;;; For example...
;;; (gen-stream-lambda MyLambdaName f)
;;; ... or ...
;;; (gen-stream-lambda AnotherLambda (fn [this input-stream output-stream ctx] ctx))
;
;(defmacro gen-lambda
;  "Given a symbol (class name) and a function of three args (this, input Object, runtime.Context),
;  Generate a named class that can be invoked as an AWS Lambda Function implementing RequestHandler
;
;  If the classname is not fully packaged qualified, the current namespace is used as the package name"
;  [lambda-class-name request-handler-fn]
;  (let [package-qualified-classname (if (string/includes? (str lambda-class-name) ".")
;                                      lambda-class-name
;                                      (symbol (str *ns* "." lambda-class-name)))
;        prefix (gensym "lambda")
;        handler-request-sym (symbol (str prefix "handleRequest"))
;        meta-data (merge (meta request-handler-fn))]
;    `(do (gen-class {:name ~package-qualified-classname
;                     :implements [com.amazonaws.services.lambda.runtime.RequestHandler]
;                     :prefix ~prefix
;                     ;:methods [[~handler-request-sym [Object com.amazonaws.services.lambda.runtime.Context] Object]]
;                     })
;         (def ~handler-request-sym ~request-handler-fn))))
;
;;; For example...
;;; (gen-lambda MyLambdaName f)
;;; ... or ...
;;; (gen-lambda AnotherLambda (fn [this input ctx] ctx))
;
;;;TODO Handle `docstring`
;(defmacro deflambda
;  "Creates a names class that can be invoked as an AWS Lambda Function.
;  If you define a two-arity lambda (input Object, runtime Context),
;  a RequestHandler is produced.
;  If you define a three-arity lambda (InputStream, OutputStream, runtime Context),
;  a RequestStreamHandler is produced.
;
;  See also: gen-stream-lambda and gen-lambda"
;  [name args & body]
;  (let [[docstring & body] (if (string? (first body)) body (cons nil body))]
;    (case (count args)
;      2 `(gen-lambda ~name (fn ~(into ['this] args) ~@body))
;      3 `(gen-stream-lambda ~name (fn ~(into ['this] args) ~@body))
;      (throw (ex-info (str "In Lambda: " name  " - Lambdas created with 'deflambda' must be 2 or 3 arguments. Found: " args)
;                      {:lamdba name
;                       :args args})))))

;; All Lambda proxy requests through API Gateway (in proxy mode) are packaged
;; up into a single JSON object.  Those details can be found here:
;; https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-set-up-simple-proxy.html#api-gateway-simple-proxy-for-lambda-input-format


;; TODO
;(defn servlet-apigw-provider
;  [service-map]
;  )

;(defmacro gen-pedestal-lambda
;  [name service-map]
;  (let [service-map (if (list? service-map) (eval service-map) service-map)]
;    `(gen-lambda ~(lambda-name name) ~(or (:io.pedestal.lambda/apigw-handler service-map)
;                                          (-> service-map
;                                              direct-apigw-provider
;                                              :io.pedestal.lambda/apigw-handler)))))


