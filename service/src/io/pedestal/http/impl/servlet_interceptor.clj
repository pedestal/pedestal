; Copyright 2023-2024 Nubank NA
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

(ns io.pedestal.http.impl.servlet-interceptor
  "Interceptors for adapting the Java HTTP Servlet interfaces."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.stacktrace :as stacktrace]
            [clojure.core.async :as async]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.interceptor.chain :as interceptor.chain]
            [io.pedestal.http.container :as container]
            [io.pedestal.http.request :as request]
            [io.pedestal.http.request.map :as request-map]
            [ring.util.response :as ring-response]
            [clojure.spec.alpha :as s]
            [io.pedestal.metrics :as metrics]
    ;; for side effects:
            io.pedestal.http.route
            io.pedestal.http.request.servlet-support)
  (:import (clojure.core.async.impl.protocols Channel)
           (clojure.lang Fn IPersistentCollection)
           (jakarta.servlet Servlet ServletRequest)
           (jakarta.servlet.http HttpServletRequest HttpServletResponse)
           (java.io File IOException InputStream OutputStreamWriter EOFException)
           (java.nio.channels ReadableByteChannel)
           (java.nio ByteBuffer)))

;;; HTTP Response

(defprotocol WriteableBody
  (default-content-type [body] "Get default HTTP content-type for `body`.")
  (write-body-to-stream [body output-stream] "Write `body` to the stream output-stream."))

(extend-protocol WriteableBody

  String

  (default-content-type [_] "text/plain")
  (write-body-to-stream [string output-stream]
    (let [writer (OutputStreamWriter. output-stream)]
      (.write writer string)
      (.flush writer)))

  IPersistentCollection

  (default-content-type [_] "application/edn")
  (write-body-to-stream [o output-stream]
    (let [writer (OutputStreamWriter. output-stream)]
      (binding [*out* writer]
        (pr o))
      (.flush writer)))

  Fn

  (default-content-type [_] nil)
  (write-body-to-stream [f output-stream]
    (f output-stream))

  File

  (default-content-type [_] "application/octet-stream")
  (write-body-to-stream [file output-stream]
    (io/copy file output-stream))

  InputStream

  (default-content-type [_] "application/octet-stream")
  (write-body-to-stream [input-stream output-stream]
    (try
      (io/copy input-stream output-stream)
      (finally (.close input-stream))))

  ReadableByteChannel

  (default-content-type [_] "application/octet-stream")

  ByteBuffer

  (default-content-type [_] "application/octet-stream")

  nil
  (default-content-type [_] nil)
  (write-body-to-stream [_ _]))

;; This is sequestered out as it confuses both clj-kondo and the Cursive linter.
#_{:clj-kondo/ignore [:syntax]}
(extend-protocol WriteableBody

  (class (byte-array 0))

  (default-content-type [_] "application/octet-stream")
  (write-body-to-stream [byte-array output-stream]
    (io/copy byte-array output-stream)))

(defn- write-body [^HttpServletResponse servlet-resp body]
  (let [output-stream (.getOutputStream servlet-resp)]
    (write-body-to-stream body output-stream)))

(def ^:private async-write-errors-fn (metrics/counter ::async-write-errors nil))

(defprotocol WriteableBodyAsync
  (write-body-async [body servlet-response resume-chan context]))

(extend-protocol WriteableBodyAsync

  Channel
  (write-body-async [body ^HttpServletResponse servlet-response resume-chan context]
    (async/go
      (loop []
        (when-let [body-part (async/<! body)]
          (try
            (write-body servlet-response body-part)
            (.flushBuffer servlet-response)
            (catch Throwable t
              ;; Defend against exhausting core.async thread pool
              ;;  -- ASYNC-169 :: http://dev.clojure.org/jira/browse/ASYNC-169
              (if (instance? EOFException t)
                (log/warn :msg "The pipe closed while async writing to the client; Client most likely disconnected."
                          :exception t
                          :src-chan body)
                (do (log/meter ::async-write-errors)
                    (async-write-errors-fn)
                    (log/error :msg "An error occurred when async writing to the client"
                               :throwable t
                               :src-chan body)))
              ;; Only close the body-ch eagerly in the failure case
              ;;  otherwise the producer (web app) is expected to close it
              ;;  when they're done.
              (async/close! body)))
          (recur)))
      (async/>! resume-chan context)
      (async/close! resume-chan)))

  ReadableByteChannel
  (write-body-async [body servlet-response resume-chan context]
    ;; Writing NIO is container specific, based on the implementation details of Response
    (container/write-byte-channel-body servlet-response body resume-chan context))

  ByteBuffer
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
    (update resp-map :headers merge {"Content-Type" (or content-type
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
  (log/trace :in 'start-servlet-async*)
  (doto (.startAsync servlet-request)
    (.setTimeout 0)))

(defn- start-servlet-async
  [{:keys [servlet-request]}]
  (when-not (request/async-started? servlet-request)
    (start-servlet-async* servlet-request)))

(defn- leave-stylobate
  [{:keys [^HttpServletRequest servlet-request] :as context}]
  (when (request/async-started? servlet-request)
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
    :else (do (send-response context)
              context)))

(defn- terminator-inject
  [context]
  (interceptor.chain/terminate-when context #(ring-response/response? (:response %))))

(defn- is-broken-pipe?
  "Checks for a broken pipe exception, which (by default) is omitted."
  [exception]
  (and (instance? IOException exception)
       (.equalsIgnoreCase "broken pipe" (ex-message exception))))

(defn default-exception-analyzer
  "The default for the :exception-analyzer option, this function is passed the
  context and a thrown exception that bubbled up to the stylobate interceptor.

  Primarily, this function determines if an exception should be logged or not;
  it can also log or otherwise report an exception itself, and then prevent
  the stylobate interceptor from reporting the exception, by returning nil.

  If a non-nil value is returned, it must be an exception, which will be logged.

  This implementation returns the exception, unless it represents a broken pipe (a common
  exception that occurs when, during a long response, the client terminates the socket connection)."
  {:added "0.7.0"}
  [_context exception]
  (when-not (is-broken-pipe? exception)
    exception))

(defn- error-stylobate
  "Makes sure we send an error response on an exception, even in the
  async case. This is just to make sure exceptions get returned
  somehow; application code should probably catch and log exceptions
  in its own interceptors."
  [error-analyzer context exception]
  (when-let [exception' (error-analyzer context exception)]
    (log/error :msg "error-stylobate triggered"
               :exception exception'
               :context context))
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

(defn- create-stylobate
  [options]
  (let [exception-analyzer (or (:exception-analyzer options)
                               default-exception-analyzer)]
    (interceptor
      {:name  ::stylobate
       :leave leave-stylobate
       :error (fn [context exception]
                (error-stylobate exception-analyzer context exception))})))

(def ^{:deprecated "0.7.0"} stylobate
  "An interceptor which primarily handles uncaught exceptions thrown
  during execution of the interceptor chain.

  This var is deprecated in 0.7.0 as it should only be added to the
  interceptor chain by [[http-interceptor-service-fn]].

  [1]: https://github.com/ring-clojure/ring/blob/master/SPEC
  [2]: http://jcp.org/aboutJava/communityprocess/final/jsr315/index.html"
  (create-stylobate nil))

(def ring-response
  "An interceptor which transmits a Ring specified response map to an
  HTTP response.

  If a later interceptor in this context throws an exception which is
  not caught, this interceptor will set the HTTP response status code
  to 500 with a generic error message. Also, if later interceptors
  fail to furnish the context with a :response map, this interceptor
  will set the HTTP response to a 500 error."
  (interceptor
    {:name  ::ring-response
     :leave leave-ring-response
     :error error-ring-response}))

(def ^{:deprecated "0.7.0"} terminator-injector
  "An interceptor which causes execution to terminate when one of
  the interceptors produces a response, as defined by
  ring.util.response/response?

  Prior to 0.7.0, this interceptor was automatically queued.
  In 0.7.0, the context is initialized with a terminator function and this
  interceptor is no longer used. "
  (interceptor
    {:name  ::terminator-injector
     :enter terminator-inject}))

(defn- error-debug
  "When an error propagates to this interceptor error fn, trap it,
  print it to the output stream of the HTTP request, and do not
  rethrow it."
  [context exception]
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
  (interceptor
    {:name  ::exception-debug
     :error error-debug}))

(defn- interceptor-service-fn
  "Returns a function which can be used as an implementation of the
  Servlet.service method. It executes the interceptors on an initial
  context map containing :servlet, :servlet-config, :servlet-request,
  and :servlet-response."
  [interceptors default-context]
  (let [error-metric-fn (metrics/counter ::base-servlet-error nil)
        *active-calls   (atom 0)]
    (metrics/gauge :io.pedestal/active-servlet-calls nil #(deref *active-calls))
    (fn [^Servlet servlet servlet-request servlet-response]
      (let [context (-> default-context
                        (assoc :servlet-request servlet-request
                               :servlet-response servlet-response
                               :servlet-config (.getServletConfig servlet)
                               :servlet servlet
                                        :request (request-map/servlet-request-map servlet servlet-request servlet-response)))]
        (log/debug :in :interceptor-service-fn
                   :context context)
        (log/counter :io.pedestal/active-servlet-calls 1)
        (swap! *active-calls inc)
        (try
          (let [final-context (interceptor.chain/execute context interceptors)]
            (log/debug :msg "Leaving servlet"
                       ;; This will be nil if the execution went async
                       :final-context final-context))
          (catch EOFException _
            (log/warn :msg "Servlet code caught EOF; The client most likely disconnected mid-response"))
          (catch Throwable t
            (log/meter ::base-servlet-error)
            (error-metric-fn)
            (log/error :msg "Servlet code threw an exception"
                       :throwable t
                       :cause-trace (with-out-str
                                      (stacktrace/print-cause-trace t))))
          (finally
            (log/counter :io.pedestal/active-servlet-calls -1)
            (swap! *active-calls dec)))))))

(s/def ::http-interceptor-service-fn-options
  (s/keys :opt-un [::exception-analyzer]))

(s/def ::exception-analyzer fn?)

(defn http-interceptor-service-fn
  "Returns a function which can be used as an implementation of the
  Servlet.service method. It executes the interceptors on an initial
  context map containing :servlet, :servlet-config, :servlet-request,
  and :servlet-response. The [[stylobate]] and [[ring-response]] interceptors
  are prepended to the sequence of interceptors.

  Options:
  :exception-analyzer - function that analyzes exceptions that propagate
  up to the stylobate interceptor, defaults to [[default-exception-analyzer]].

  This is normally called automatically from io.pedestal.http/service-fn."
  ([interceptors] (http-interceptor-service-fn interceptors {}))
  ([interceptors default-context]
   (http-interceptor-service-fn interceptors default-context nil))
  ([interceptors default-context options]
   (interceptor-service-fn
     (into [(create-stylobate options)
            ring-response]
           interceptors)
     (-> default-context
         terminator-inject
         (interceptor.chain/on-enter-async start-servlet-async)))))
