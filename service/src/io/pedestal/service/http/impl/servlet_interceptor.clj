(ns io.pedestal.service.http.impl.servlet-interceptor
  "Interceptors for adapting the Java HTTP Servlet interfaces."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.stacktrace :as stacktrace]
            [io.pedestal.service.log :as log]
            [io.pedestal.service.interceptor :as interceptor :refer [definterceptor]]
            [io.pedestal.service.http.route :as route]
            [io.pedestal.service.impl.interceptor :as interceptor-impl]
            [ring.util.response :as ring-response])
  (:import (javax.servlet Servlet ServletConfig)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           (java.io OutputStreamWriter OutputStream)))

;;; HTTP Response

(defprotocol WriteableBody
  (default-content-type [body] "Get default HTTP content-type for `body`.")
  (write-body-to-stream [body output-stream] "Write `body` to the stream output-stream."))

(extend-protocol WriteableBody
  (class (byte-array 0))
  (default-content-type [_] "application/octet-stream")
  (write-body-to-stream [byte-array output-stream]
    (io/copy byte-array output-stream))

  String
  (default-content-type [_] "text/plain")
  (write-body-to-stream [string output-stream]
    (let [writer (OutputStreamWriter. output-stream)]
      (.write writer string)
      (.flush writer)))

  clojure.lang.IPersistentCollection
  (default-content-type [_] "application/edn")
  (write-body-to-stream [o output-stream]
    (let [writer (OutputStreamWriter. output-stream)]
      (binding [*out* writer]
        (pr o))
      (.flush writer)))

  clojure.lang.Fn
  (default-content-type [_] nil)
  (write-body-to-stream [f output-stream]
    (f output-stream))

  java.io.File
  (default-content-type [_] "application/octet-stream")
  (write-body-to-stream [file output-stream]
    (io/copy file output-stream))

  java.io.InputStream
  (default-content-type [_] "application/octet-stream")
  (write-body-to-stream [input-stream output-stream]
    (io/copy input-stream output-stream))

  nil
  (default-content-type [_] nil)
  (write-body-to-stream [_ _] ()))

(defn- write-body [^HttpServletResponse servlet-resp resp-map]
  (let [{:keys [body]} resp-map
        output-stream (.getOutputStream servlet-resp)]
    (write-body-to-stream body output-stream)))

;; Should we also set character encoding explicitly - if so, where
;; should it be stored in the response map, headers? If not,
;; should we provide help for adding it to content-type string?
(defn set-header [^HttpServletResponse servlet-resp h vs]
  (cond
   (= h "Content-Type") (.setContentType servlet-resp vs)
   (= h "Content-Length") (.setContentLength servlet-resp (Integer/parseInt vs))
   (string? vs) (.setHeader servlet-resp h vs)
   (sequential? vs) (doseq [v vs] (.addHeader servlet-resp h v))
   :else
   (throw (ex-info "Invalid header value" {:value vs}))))

(defn get-default-content-type
  [{:keys [headers body] :or {headers {}} :as resp-map}]
  (let [content-type (headers "Content-Type")]
    (update-in resp-map [:headers] merge {"Content-Type" (or content-type
                                                             (default-content-type body))})))

(defn set-response [^HttpServletResponse servlet-resp resp-map]
  (let [{:keys [status headers]} (get-default-content-type resp-map)]
    (.setStatus servlet-resp status)
    (doseq [[k vs] headers]
      (set-header servlet-resp k vs))))

(defn- send-response [^HttpServletResponse servlet-resp resp-map]
  (when-not (.isCommitted servlet-resp)
    (set-response servlet-resp resp-map))
  (write-body servlet-resp resp-map)
  (.flushBuffer servlet-resp))

;;; HTTP Request

(defn- request-headers [^HttpServletRequest servlet-req]
  (loop [out (transient {})
         names (enumeration-seq (.getHeaderNames servlet-req))]
    (if (seq names)
      (let [key (first names)]
        (recur (assoc! out (.toLowerCase ^String key)
                       (.getHeader servlet-req key))
               (rest names)))
      (persistent! out))))

(defn- base-request-map [servlet ^HttpServletRequest servlet-req servlet-resp]
  {:server-port       (.getServerPort servlet-req)
   :server-name       (.getServerName servlet-req)
   :remote-addr       (.getRemoteAddr servlet-req)
   :uri               (.getRequestURI servlet-req)
   :query-string      (.getQueryString servlet-req)
   :scheme            (keyword (.getScheme servlet-req))
   :request-method    (keyword (.toLowerCase (.getMethod servlet-req)))
   :headers           (request-headers servlet-req)
   :body              (.getInputStream servlet-req)
   :servlet           servlet
   :servlet-request   servlet-req
   :servlet-response  servlet-resp
   :servlet-context   (.getServletContext ^ServletConfig servlet)
   ::protocol         (.getProtocol servlet-req)
   ::async-supported? (.isAsyncSupported servlet-req)})

(defn- add-content-type [req-map ^HttpServletRequest servlet-req]
  (if-let [type (.getContentType servlet-req)]
    (assoc! req-map :content-type type)
    req-map))

(defn- add-content-length [req-map ^HttpServletRequest servlet-req]
  (let [c (.getContentLength servlet-req)]
    (if (neg? c)
      req-map
      (assoc! req-map :content-length c))))

(defn- add-character-encoding [req-map ^HttpServletRequest servlet-req]
  (if-let [e (.getCharacterEncoding servlet-req)]
    (assoc! req-map :character-encoding e)
    req-map))

(defn- add-ssl-client-cert [req-map ^HttpServletRequest servlet-req]
  (if-let [c (.getAttribute servlet-req "javax.servlet.request.X509Certificate")]
    (assoc! req-map :ssl-client-cert c)
    req-map))

(defn- request-map [^Servlet servlet ^HttpServletRequest servlet-req servlet-resp]
  (-> (base-request-map servlet servlet-req servlet-resp)
      transient
      (add-content-length servlet-req)
      (add-content-type servlet-req)
      (add-character-encoding servlet-req)
      (add-ssl-client-cert servlet-req)
      persistent!))

(defn- start-async
  "Begins an asynchronous response to a request."
  [^HttpServletRequest servlet-request]
  ;; TODO: fix?
  ;; Embedded Tomcat doesn't allow .startAsync by default, even if the
  ;; Servlet was annotated with asyncSupported=true. We have to
  ;; explicitly set it on the request.
  ;; See http://stackoverflow.com/questions/7749350
  (.setAttribute servlet-request "org.apache.catalina.ASYNC_SUPPORTED" true)
  (doto (.startAsync servlet-request)
    (.setTimeout 0)))

(defn- send-error [servlet-response message]
  (log/info :msg "sending error"
            :message message)
  (send-response servlet-response {:status 500 :body message}))

(defn- enter-stylobate
  [{:keys [servlet servlet-request servlet-response] :as context}]
  (assoc context :request
         (request-map servlet servlet-request servlet-response)))

(defn- leave-stylobate
  [{:keys [^HttpServletRequest servlet-request]
    async? ::async? :as context}]
  (when async? (.complete (.getAsyncContext servlet-request)))
  context)

(defn- leave-ring-response
  [{:keys [^HttpServletRequest servlet-request servlet-response response]
    :as context}]
  (if response
    (send-response servlet-response response)
    (send-error servlet-response "Internal server error: no response"))
  context)

(defn- terminator-inject
  [context]
  (interceptor-impl/terminate-when context #(ring-response/response? (:response %))))

(defn- error-stylobate
  "Makes sure we send an error response on an exception, even in the
  async case. This is just to make sure exceptions get returned
  somehow; application code should probably catch and log exceptions
  in its own interceptors."
  [{:keys [servlet-response] :as context} exception]
  (log/error :msg "error-stylobate triggered"
             :exception exception
             :context context)
  context)

(defn- error-ring-response
  "Makes sure we send an error response on an exception, even in the
  async case. This is just to make sure exceptions get returned
  somehow; application code should probably catch and log exceptions
  in its own interceptors."
  [{:keys [servlet-response] :as context} exception]
  (log/error :msg "error-ring-response triggered"
             :exception exception
             :context context)
  (send-error servlet-response "Internal server error: exception")
  context)

(defn- error-debug
  "When an error propogates to this interceptor error fn, trap it,
  print it to the output stream of the HTTP request, and do not
  rethrow it."
  [{:keys [servlet-response] :as context} exception]
  (assoc context
    :response (ring-response/response
               (with-out-str (println "Error processing request!")
                 (println "Exception:\n")
                 (stacktrace/print-cause-trace exception)
                 (println "\nContext:\n")
                 (pprint/pprint context)))))

(definterceptor exception-debug
  "An interceptor which catches errors, renders them to readable text
  and sends them to the user. This interceptor is intended for
  development time assistance in debugging problems in pedestal
  services. Including it in interceptor paths on production systems
  may present a security risk by exposing call stacks of the
  application when exceptions are encountered."
  (interceptor/interceptor
   :name ::exception-debug
   :error error-debug))

(defn- pause-stylobate
  [{:keys [servlet-request], async? ::async?, :as context}]
  (when-not async? (start-async servlet-request))
  (assoc context ::async? true))

(definterceptor stylobate
  "An interceptor which creates favorable pre-conditions for further
  io.pedestal.service.interceptors, and handles all post-conditions for
  processing an interceptor chain. It expects a context map
  with :servlet-request, :servlet-response, and :servlet keys.

  After entering this interceptor, the context will contain a new
  key :request, the value will be a request map adhering to the Ring
  specification[1].

  This interceptor supports asynchronous responses as defined in the
  Java Servlet Specification[2] version 3.0. On leaving this
  interceptor, if the context was flagged as asynchronous, all
  asynchronous resources will be cleaned. Pausing this interceptor
  will inform the servlet container that the response will be
  delivered asynchronously, and flag the context this interceptor is
  processing as asynchronous.

  If a later interceptor in this context throws an exception which is
  not caught, this interceptor will log the error but not communicate
  any details to the client.

  [1]: https://github.com/ring-clojure/ring/blob/master/SPEC
  [2]: http://jcp.org/aboutJava/communityprocess/final/jsr315/index.html"

  (interceptor/interceptor
   :name ::stylobate
   :enter enter-stylobate
   :leave leave-stylobate
   :pause pause-stylobate
   :error error-stylobate))

(definterceptor ring-response
  "An interceptor which transmits a Ring specified response map to an
  HTTP response.

  If a later interceptor in this context throws an exception which is
  not caught, this interceptor will set the HTTP response status code
  to 500 with a generic error message. Also, if later interceptors
  fail to furnish the context with a :response map, this interceptor
  will set the HTTP response to a 500 error."
  (interceptor/interceptor
   :name ::ring-response
   :leave leave-ring-response
   :error error-ring-response))

(definterceptor terminator-injector
  "An interceptor which causes a interceptor to terminate when one of
  the interceptors produces a response, as defined by
  ring.util.response/response?"
  (interceptor/before
   ::terminator-injector
   terminator-inject))

(defn- interceptor-service-fn
  "Returns a function which can be used as an implementation of the
  Servlet.service method. It executes the interceptors on an initial
  context map containing :servlet, :servlet-config, :servlet-request,
  and :servlet-response."
  [interceptors default-context]
  (fn [^Servlet servlet servlet-request servlet-response]
    (let [context (merge default-context
                         {:servlet-request servlet-request
                          :servlet-response servlet-response
                          :servlet-config (.getServletConfig servlet)
                          :servlet servlet})]
      (try
        (let [final-context (interceptor-impl/execute
                             (apply interceptor-impl/enqueue context interceptors))]
          (log/debug :msg "Leaving servlet"
                    :final-context final-context))
        (catch Throwable t
          (log/error :msg "Servlet code threw an exception"
                     :throwable t
                     :cause-trace (with-out-str
                                    (stacktrace/print-cause-trace t))))))))

(defn http-interceptor-service-fn
  "Returns a function which can be used as an implementation of the
  Servlet.service method. It executes the interceptors on an initial
  context map containing :servlet, :servlet-config, :servlet-request,
  and :servlet-response. The terminator-injector, stylobate,
  and ring-response are prepended to the sequence of interceptors."
  ([interceptors] (http-interceptor-service-fn interceptors {}))
  ([interceptors default-context]
     (interceptor-service-fn
       (concat [terminator-injector
                stylobate
                ring-response]
               interceptors)
       default-context)))
