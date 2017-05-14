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

(ns io.pedestal.http.impl.servlet-interceptor
  "Interceptors for adapting the Java HTTP Servlet interfaces."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.stacktrace :as stacktrace]
            [clojure.core.async :as async]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor]
            [io.pedestal.interceptor.helpers :as interceptor]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor.chain :as interceptor.chain]
            [io.pedestal.http.container :as container]
            [io.pedestal.http.request :as request]
            [io.pedestal.http.request.map :as request-map]
            [io.pedestal.http.request.zerocopy :as request-zerocopy]
            [ring.util.response :as ring-response])
  (:import (javax.servlet Servlet ServletRequest ServletConfig)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           (java.io OutputStreamWriter OutputStream EOFException)
           (java.nio.channels ReadableByteChannel)
           (java.nio ByteBuffer)))

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
    (try
      (io/copy input-stream output-stream)
      (finally (.close input-stream))))

  java.nio.channels.ReadableByteChannel
  (default-content-type [_] "application/octet-stream")

  java.nio.ByteBuffer
  (default-content-type [_] "application/octet-stream")

  nil
  (default-content-type [_] nil)
  (write-body-to-stream [_ _] ()))

(defn- write-body [^HttpServletResponse servlet-resp body]
  (let [output-stream (.getOutputStream servlet-resp)]
    (write-body-to-stream body output-stream)))

(defprotocol WriteableBodyAsync
  (write-body-async [body servlet-response resume-chan context]))

(extend-protocol WriteableBodyAsync

  clojure.core.async.impl.protocols.Channel
  (write-body-async [body servlet-response resume-chan context]
    (async/go
      (loop []
        (when-let [body-part (async/<! body)]
          (try
            (write-body servlet-response body-part)
            (.flushBuffer ^HttpServletResponse servlet-response)
            (catch EOFException e
              (log/warn :msg "Client unavailable for async write. Closing :src-chan."
                        :src-chan body)
              (async/close! body))
            (catch Throwable t
              ;; Defend against exhausting core.async thread pool
              ;;  -- ASYNC-169 :: http://dev.clojure.org/jira/browse/ASYNC-169
              (if (instance? EOFException t)
                (log/warn :msg "The pipe closed while async writing to the client; Client most likely disconnected."
                          :exception t
                          :src-chan body)
                (do (log/meter ::async-write-errors)
                    (log/error :msg "An error occured when async writing to the client"
                               :throwable t
                               :src-chan body)))
              ;; Only close the body-ch eagerly in the failure case
              ;;  otherwise the producer (web app) is expected to close it
              ;;  when they're done.
              (async/close! body)))
          (recur)))
      (async/>! resume-chan context)
      (async/close! resume-chan)))

  java.nio.channels.ReadableByteChannel
  (write-body-async [body servlet-response resume-chan context]
    ;; Writing NIO is container specific, based on the implementation details of Response
    (container/write-byte-channel-body servlet-response body resume-chan context))

  java.nio.ByteBuffer
  (write-body-async [body servlet-response resume-chan context]
    ;; Writing NIO is container specific, based on the implementation details of Response
    (container/write-byte-buffer-body servlet-response body resume-chan context)))

;; Should we also set character encoding explicitly - if so, where
;; should it be stored in the response map, headers? If not,
;; should we provide help for adding it to content-type string?
(defn- set-header [^HttpServletResponse servlet-resp h vs]
  (cond
   (= h "Content-Type") (.setContentType servlet-resp vs)
   (= h "Content-Length") (.setContentLengthLong servlet-resp (Long/parseLong vs))
   (string? vs) (.setHeader servlet-resp h vs)
   (sequential? vs) (doseq [v vs] (.addHeader servlet-resp h v))
   :else
   (throw (ex-info "Invalid header value" {:value vs}))))

(defn- set-default-content-type
  [{:keys [headers body] :or {headers {}} :as resp-map}]
  (let [content-type (headers "Content-Type")]
    (update-in resp-map [:headers] merge {"Content-Type" (or content-type
                                                             (default-content-type body))})))

(defn set-response
  ([^HttpServletResponse servlet-resp resp-map]
     (let [{:keys [status headers]} (set-default-content-type resp-map)]
       (.setStatus servlet-resp status)
       (doseq [[k vs] headers]
         (set-header servlet-resp k vs)))))

(defn- send-response
  [{:keys [^HttpServletResponse servlet-response response] :as context}]
  (when-not (.isCommitted servlet-response)
    (set-response servlet-response response))
  (let [body (:body response)]
    (if (satisfies? WriteableBodyAsync body)
      (write-body-async body servlet-response (::resume-channel context) context)
      (do
        (write-body servlet-response body)
        (.flushBuffer servlet-response)))))

;;; Async handling and Provider bootstrapping

(defn- start-servlet-async*
  "Begins an asynchronous response to a request."
  [^ServletRequest servlet-request]
  ;; TODO: fix?
  ;; Embedded Tomcat doesn't allow .startAsync by default, even if the
  ;; Servlet was annotated with asyncSupported=true. We have to
  ;; explicitly set it on the request.
  ;; See http://stackoverflow.com/questions/7749350
  (.setAttribute servlet-request "org.apache.catalina.ASYNC_SUPPORTED" true)
  (doto (.startAsync servlet-request)
    (.setTimeout 0)))

(defn- servlet-async? [{:keys [servlet-request] :as context}]
  (request/async-started? servlet-request))

(defn- start-servlet-async
  [{:keys [servlet-request async?] :as context}]
  (when-not (async? context)
    (start-servlet-async* servlet-request)))

(defn- enter-stylobate
  [{:keys [servlet servlet-request servlet-response] :as context}]
  (-> context
      (assoc :request (request-map/servlet-request-map servlet servlet-request servlet-response)
             ;; While the zero-copy saves GCs and Heap utilization, Pedestal is still dominated by Interceptors
             ;:request (request-zerocopy/call-through-request servlet-request
             ;                                                {:servlet servlet
             ;                                                 :servlet-request servlet-request
             ;                                                 :servlet-response servlet-response})
             :async? servlet-async?)
      (update-in [:enter-async] (fnil conj []) start-servlet-async)))

(defn- leave-stylobate
  [{:keys [^HttpServletRequest servlet-request async?] :as context}]
  (when (async? context)
    (.complete (.getAsyncContext servlet-request)))
  context)

(defn- send-error
  [context message]
  (log/info :msg "sending error" :message message)
  (send-response (assoc context :response {:status 500 :body message})))

(defn- leave-ring-response
  [{{body :body :as response} :response :as context}]
  (log/debug :in :leave-ring-response :response response)

  (cond
   (nil? response) (do
                     (send-error context "Internal server error: no response")
                     context)
   (satisfies? WriteableBodyAsync body) (let [chan (::resume-channel context (async/chan))]
                                          (send-response (assoc context ::resume-channel chan))
                                          chan)
   true (do (send-response context)
            context)))

(defn- terminator-inject
  [context]
  (interceptor.chain/terminate-when context #(ring-response/response? (:response %))))

(defn- error-stylobate
  "Makes sure we send an error response on an exception, even in the
  async case. This is just to make sure exceptions get returned
  somehow; application code should probably catch and log exceptions
  in its own interceptors."
  [{:keys [servlet-response] :as context} exception]
  (log/error :msg "error-stylobate triggered"
             :exception exception
             :context context)
  (leave-stylobate context))

(defn- error-ring-response
  "Makes sure we send an error response on an exception, even in the
  async case. This is just to make sure exceptions get returned
  somehow; application code should probably catch and log exceptions
  in its own interceptors."
  [context exception]
  (log/error :msg "error-ring-response triggered"
             :exception exception
             :context context)
  (send-error context "Internal server error: exception")
  context)

(def stylobate
  "An interceptor which creates favorable pre-conditions for further
  io.pedestal.interceptors, and handles all post-conditions for
  processing an interceptor chain. It expects a context map
  with :servlet-request, :servlet-response, and :servlet keys.

  After entering this interceptor, the context will contain a new
  key :request, the value will be a request map adhering to the Ring
  specification[1].

  This interceptor supports asynchronous responses as defined in the
  Java Servlet Specification[2] version 3.0. On leaving this
  interceptor, if the servlet request has been set asynchronous, all
  asynchronous resources will be closed. Pausing this interceptor will
  inform the servlet container that the response will be delivered
  asynchronously.

  If a later interceptor in this context throws an exception which is
  not caught, this interceptor will log the error but not communicate
  any details to the client.

  [1]: https://github.com/ring-clojure/ring/blob/master/SPEC
  [2]: http://jcp.org/aboutJava/communityprocess/final/jsr315/index.html"

  (io.pedestal.interceptor/interceptor {:name ::stylobate
                                        :enter enter-stylobate
                                        :leave leave-stylobate
                                        :error error-stylobate}))

(def ring-response
  "An interceptor which transmits a Ring specified response map to an
  HTTP response.

  If a later interceptor in this context throws an exception which is
  not caught, this interceptor will set the HTTP response status code
  to 500 with a generic error message. Also, if later interceptors
  fail to furnish the context with a :response map, this interceptor
  will set the HTTP response to a 500 error."
  (io.pedestal.interceptor/interceptor {:name ::ring-response
                                        :leave leave-ring-response
                                        :error error-ring-response}))

(def terminator-injector
  "An interceptor which causes a interceptor to terminate when one of
  the interceptors produces a response, as defined by
  ring.util.response/response?"
  (interceptor/before
   ::terminator-injector
   terminator-inject))

(defn- error-debug
  "When an error propagates to this interceptor error fn, trap it,
  print it to the output stream of the HTTP request, and do not
  rethrow it."
  [{:keys [servlet-response] :as context} exception]
  (log/error :msg "Dev interceptor caught an exception; Forwarding it as the response."
             :exception exception)
  (assoc context
         :response (-> (ring-response/response
                        (with-out-str (println "Error processing request!")
                          (println "Exception:\n")
                          (stacktrace/print-cause-trace exception)
                          (println "\nContext:\n")
                          (pprint/pprint context)))
                       (ring-response/status 500))))

(def exception-debug
  "An interceptor which catches errors, renders them to readable text
  and sends them to the user. This interceptor is intended for
  development time assistance in debugging problems in pedestal
  services. Including it in interceptor paths on production systems
  may present a security risk by exposing call stacks of the
  application when exceptions are encountered."
  (io.pedestal.interceptor/interceptor {:name ::exception-debug
                                        :error error-debug}))

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
      (log/debug :in :interceptor-service-fn
                 :context context)
      (log/counter :io.pedestal/active-servlet-calls 1)
      (try
        (let [final-context (interceptor.chain/execute context interceptors)]
          (log/debug :msg "Leaving servlet"
                     :final-context final-context))
        (catch EOFException e
          (log/warn :msg "Servlet code caught EOF; The client most likely disconnected mid-response"))
        (catch Throwable t
          (log/meter ::base-servlet-error)
          (log/error :msg "Servlet code threw an exception"
                     :throwable t
                     :cause-trace (with-out-str
                                    (stacktrace/print-cause-trace t))))
        (finally
          (log/counter :io.pedestal/active-servlet-calls -1))))))

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
