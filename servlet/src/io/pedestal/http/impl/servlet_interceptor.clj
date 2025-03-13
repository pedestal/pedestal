; Copyright 2023-2025 Nubank NA
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
            [clojure.core.async :as async]
            [io.pedestal.http.response :as response]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.connector.dev :as dev]
            [io.pedestal.http.container :as container]
            [io.pedestal.http.request.map :as request-map]
            [io.pedestal.metrics :as metrics]
            [io.pedestal.service.websocket :as ws :refer [InitializeWebSocket WebSocketChannel]]
            [io.pedestal.service.data :refer [convert]]
    ;; for side effects:
            io.pedestal.http.route
            [io.pedestal.service.impl :as impl])
  (:import (clojure.core.async.impl.protocols ReadPort)
           (io.pedestal.websocket FnEndpoint)
           (jakarta.servlet Servlet ServletRequest)
           (jakarta.servlet.http HttpServletResponse HttpServletRequest)
           (clojure.lang Fn IPersistentCollection)
           (jakarta.websocket CloseReason CloseReason$CloseCodes MessageHandler$Whole Session)
           (jakarta.websocket.server ServerContainer ServerEndpointConfig ServerEndpointConfig$Builder)
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


  ;; These next two have no implementation of write-body-to-stream because
  ;; they extend the WriteableBodyAsync protocol.

  ReadableByteChannel

  (default-content-type [_] "application/octet-stream")

  ByteBuffer

  (default-content-type [_] "application/octet-stream")

  nil
  (default-content-type [_] nil)
  (write-body-to-stream [_ _]))

(extend (Class/forName "[B")

  WriteableBody

  {:default-content-type (fn [_] "application/octet-stream")
   :write-body-to-stream
   (fn [^bytes byte-array output-stream]
     (io/copy byte-array output-stream))})


(defn- write-body [^HttpServletResponse servlet-resp body]
  (let [output-stream (.getOutputStream servlet-resp)]
    (write-body-to-stream body output-stream)))

(def ^:private async-write-errors-fn (metrics/counter ::async-write-errors nil))

(defprotocol WriteableBodyAsync
  (write-body-async [body servlet-response resume-chan context]))

(extend-protocol WriteableBodyAsync

  ReadPort
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
                (do (async-write-errors-fn)
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

(defn set-response
  ([^HttpServletResponse servlet-resp resp-map]
   (let [{:keys [status headers]} resp-map]
     (.setStatus servlet-resp status)
     (doseq [[k vs] headers]
       (set-header servlet-resp k vs)))))

(defn- send-response
  [{:keys [^HttpServletResponse servlet-response response] :as context}]
  (let [{:keys [body]} response]
    (when-not (.isCommitted servlet-response)
      (set-response servlet-response response))
    (if (satisfies? WriteableBodyAsync body)
      (write-body-async body servlet-response (::resume-channel context) context)
      (do
        (write-body servlet-response body)
        (.flushBuffer servlet-response)))
    context))

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
  [{:keys [^HttpServletRequest servlet-request]}]
  (when-not (.isAsyncStarted servlet-request)
    (start-servlet-async* servlet-request)))

(defn- leave-stylobate
  [{:keys [^HttpServletRequest servlet-request] :as context}]
  (when (.isAsyncStarted servlet-request)
    (.complete (.getAsyncContext servlet-request)))
  context)

(defn- send-error
  [context message]
  (log/info :msg "sending error" :message message)
  (send-response (assoc context :response {:status  500
                                           :headers {"Content-Type" "text/plain"}
                                           :body    message})))

(defn- leave-ring-response
  [{{body :body :as response} :response :as context}]
  (log/debug :in :leave-ring-response :response response)

  (cond
    ;; i.e., was WebSocket upgrade request?
    (not (response/response-expected? context))
    context

    (nil? response)
    (do
      (send-error context "Internal server error: no response")
      context)

    (satisfies? WriteableBodyAsync body)
    (let [chan (::resume-channel context (async/chan))]
      ;; Create a channel that will convey the context after the response
      ;; has been asynchronously written.  Start copying (which will continue
      ;; inside the servlet container's threads) and return the channel.
      (send-response (assoc context ::resume-channel chan))
      chan)

    :else
    (send-response context)))

(defn- is-broken-pipe?
  "Checks for a broken pipe exception, which (by default) is omitted."
  [exception]
  (cond
    (nil? exception)
    false

    (and (instance? IOException exception)
         (.equalsIgnoreCase "broken pipe" (ex-message exception)))
    true

    :else
    (let [next (ex-cause exception)]
      (when-not (identical? exception next)
        (recur next)))))

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

(def apply-default-content-type
  "An interceptor that will apply a default content type header,
  if none has been supplied, and a default can be identified from
  the response body."
  (interceptor
    {:name  ::apply-default-content-type
     :leave (fn [context]
              (let [{:keys [response]} context
                    {:keys [headers body]} response
                    content-type (get headers "Content-Type")
                    default-type (when (nil? content-type)
                                   (default-content-type body))]
                (cond-> context
                  default-type (assoc-in [:response :headers "Content-Type"] default-type))))}))

(def ^{:deprecated "0.8.0"} exception-debug
  "An interceptor which catches errors, renders them to readable text
  and sends them to the user. This interceptor is intended for
  development time assistance in debugging problems in pedestal
  services. Including it in interceptor paths on production systems
  may present a security risk by exposing call stacks of the
  application when exceptions are encountered.

  DEPRECATED: Use io.pedestal.connector.dev/uncaught-exception instead."
  (assoc dev/uncaught-exception :name ::exception-debug))

(defn- interceptor-service-fn
  "Returns a function which can be used as an implementation of the
  Servlet.service method. It executes the interceptors on an initial
  context map containing :servlet, :servlet-config, :servlet-request,
  and :servlet-response."
  [interceptors initial-context]
  (let [error-metric-fn (metrics/counter ::base-servlet-error nil)
        *active-calls   (atom 0)]
    (metrics/gauge :io.pedestal/active-servlet-calls nil #(deref *active-calls))
    (fn [^Servlet servlet servlet-request servlet-response]
      (let [context (-> initial-context
                        (assoc :servlet-request servlet-request
                               :websocket-channel-source servlet-request
                               :servlet-response servlet-response
                               :servlet-config (.getServletConfig servlet)
                                               :servlet servlet
                                               :request (request-map/servlet-request-map servlet servlet-request servlet-response)))]
        (log/debug :in :interceptor-service-fn
                   :context context)
        (swap! *active-calls inc)
        (try
          (let [final-context (chain/execute context interceptors)]
            (log/debug :msg "Leaving servlet"
                       ;; This will be nil if the execution went async
                       :final-context final-context))
          (catch EOFException _
            (log/warn :msg "Servlet code caught EOF; The client most likely disconnected mid-response"))
          (catch Throwable t
            (error-metric-fn)
            (log/error :msg "Servlet code threw an exception"
                       :throwable t
                       :cause-trace (impl/format-exception t)))
          (finally
            (swap! *active-calls dec)))))))


(defn http-interceptor-service-fn
  "Returns a function which can be used as an implementation of the
  Servlet.service method. It executes the interceptors (which must be
  already converted into Interceptor records) on an initial
  context map containing :servlet, :servlet-config, :servlet-request,
  and :servlet-response. The [[stylobate]] and [[ring-response]] interceptors
  are prepended to the sequence of interceptors.

  Options:
  :exception-analyzer - function that analyzes exceptions that propagate
  up to the stylobate interceptor, defaults to [[default-exception-analyzer]].

  This is normally called automatically from io.pedestal.http/service-fn."
  ([interceptors] (http-interceptor-service-fn interceptors {}))
  ([interceptors initial-context]
   (http-interceptor-service-fn interceptors initial-context nil))
  ([interceptors initial-context options]
   (interceptor-service-fn
     (into [(create-stylobate options)
            ring-response
            apply-default-content-type]
           interceptors)
     (-> initial-context
         response/terminate-when-response
         (chain/on-enter-async start-servlet-async)))))

;;; Support for WebSockets, in the context of io.pedestal.service.websocket

(def ^:private close-codes
  {1000 :normal
   1001 :going-away
   1002 :protocol-error
   1003 :unsupported
   1005 :no-status-received
   1006 :abnormal
   1007 :invalid-payload-data
   1008 :policy-violation
   1009 :message-too-big
   1010 :mandatory-extension
   1011 :internal-server-error
   1015 :tls-handshake})

(defn- convert-close-reason
  [^CloseReason reason]
  (-> reason .getCloseCode .getCode  (close-codes :unknown)))

(defn- message-handler
  ^MessageHandler$Whole [ws-channel *proc f]
  (reify MessageHandler$Whole
    (onMessage [_ message]
      (f ws-channel @*proc message))))

(defn- websocket-channel-callback
  "Creates the callback from the FnEndpoint to handle lifecycle (open, close, error) of the WebSocket."
  [request *ws-channel ws-opts]
  (let [*proc (atom nil)]
    (fn [event-type ^Session session extra]
      (case event-type
        :on-open
        (let [ws-channel (reify WebSocketChannel
                           (on-text [this callback]
                             (.addMessageHandler session String (message-handler this *proc callback)))
                           (on-binary [this format callback]
                             (.addMessageHandler session

                                                 ByteBuffer
                                                 (message-handler this *proc
                                                                  (fn [ws-channel proc data]
                                                                    (callback ws-channel proc (convert format data))))))
                           (send-text! [_ string]
                             (let [remote (.getBasicRemote session)]
                               (.sendText remote string)
                               true))
                           (send-binary! [_ data]
                             (let [remote (.getBasicRemote session)]
                               (.sendBinary remote (convert :byte-buffer data))
                               true))
                           (close! [_]
                             (.close session
                                     (CloseReason. CloseReason$CloseCodes/NORMAL_CLOSURE nil))))]
          (when-let [f (:on-open ws-opts)]
            (reset! *proc (f ws-channel request)))
          (when-let [f (:on-text ws-opts)]
            (ws/on-text ws-channel f))
          (when-let [f (:on-binary ws-opts)]
            (ws/on-binary ws-channel :byte-buffer f))
          (deliver *ws-channel ws-channel))

        :on-error
        ;; Would be nice to add, but ...

        :on-close
        (when-let [f (:on-close ws-opts)]
          (f @*ws-channel @*proc (convert-close-reason extra)))))))

(defn- create-server-endpoint-config
  ^ServerEndpointConfig [^String path request *ws-channel ws-opts]
  (let [callback (websocket-channel-callback request *ws-channel ws-opts)
        {:keys [subprotocols configure]} ws-opts
        builder  (ServerEndpointConfig$Builder/create FnEndpoint path)
        _        (do
                   (when subprotocols
                     (.subprotocols builder subprotocols))
                   (when configure
                     (configure builder)))
        config   (.build builder)]
    (.put (.getUserProperties config) FnEndpoint/USER_ATTRIBUTE_KEY callback)
    config))

(defn- initialize-websocket*
  [^HttpServletRequest servlet-request context ws-opts]
  (try
    (let [{:keys [request servlet-response route]} context
          _               (do
                            (assert servlet-response)
                            (assert route))
          servlet-context (.getServletContext servlet-request)
          container       ^ServerContainer (.getAttribute servlet-context "jakarta.websocket.server.ServerContainer")
          _               (assert container)
          *ws-channel     (promise)
          ;; TODO: May need to do some transformation of the :path
          config          (create-server-endpoint-config
                            (:path route)
                            request
                            *ws-channel
                            ws-opts)]
      ;; The path-params are included because they are in the Servlet API, and that's normally the only way
      ;; the FnEndpoint class would have access to them, but in a Pedestal app, if they are needed
      ;; they will be in the request map prior to calling upgrade-request-to-websocket.
      (.upgradeHttpToWebSocket container
                               servlet-request
                               servlet-response
                               config
                               {})
      ;; Note that the creation of the FnEndpoint happens later (I believe once this request has been processed)
      ;; so we can't attached the WebSocketChannel instance here ... we don't it.  That makes the :on-load
      ;; callback important, as that's the only way the application can find the WSC in order to send messages
      ;; to the client, etc.
      (-> context
          response/disable-response
          chain/terminate))
    (catch Exception e
      (prn e))))

(extend-type HttpServletRequest

  InitializeWebSocket

  (initialize-websocket [request context ws-opts]
    (initialize-websocket* request context ws-opts)))
