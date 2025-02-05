; Copyright 2023-2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.websocket
  "Utilities needed by Pedestal containers (such as [[io.pedestal.http.jetty]]) to implement
  WebSocket support using default Servlet API functionality, as well as utilities
  for applications that make use Pedestal's websocket support."
  {:added "0.7.0"}
  (:require [clojure.core.async :as async :refer [go-loop put!]]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.internal :refer [deprecated]]
            [io.pedestal.log :as log])
  (:import (io.pedestal.websocket FnEndpoint)
           (jakarta.servlet.http HttpServletRequest)
           (jakarta.websocket EndpointConfig SendHandler Session MessageHandler$Whole RemoteEndpoint$Async)
           (jakarta.websocket.server ServerContainer ServerEndpointConfig ServerEndpointConfig$Builder)
           (java.nio ByteBuffer)
           (java.util HashMap)))

(defn- message-handler
  ^MessageHandler$Whole [session-object f]
  (reify MessageHandler$Whole
    (onMessage [_ message]
      (f session-object message))))

(def ^:private ^String session-object-key "io.pedestal.websocket.session-object")

(defn- make-endpoint-delegate-callback
  "Given a map defining the WebService behavior, returns a function that will ultimately be used
  by the FnEndpoint instance."
  [ws-map]
  (let [{:keys [on-open
                on-close
                on-error
                on-text
                on-binary
                idle-timeout-ms]} ws-map
        maybe-invoke-callback (fn [f ^Session session event-value]
                                (when f
                                  (let [session-object (-> session
                                                           .getUserProperties
                                                           (.get session-object-key))]
                                    (f session-object session event-value))))
        full-on-open          (fn [^Session session ^EndpointConfig config]
                                (let [session-object (when on-open
                                                       (on-open session config))]
                                  ;; Store this for on-close, on-error
                                  (-> session .getUserProperties (.put session-object-key session-object))

                                  (when idle-timeout-ms
                                    (.setMaxIdleTimeout session (long idle-timeout-ms)))

                                  (when on-text
                                    (.addMessageHandler session String (message-handler session-object on-text)))

                                  (when on-binary
                                    (.addMessageHandler session ByteBuffer (message-handler session-object on-binary)))))]
    (fn [event-type ^Session session event-value]
      (case event-type
        :on-open (full-on-open session event-value)
        :on-error (maybe-invoke-callback on-error session event-value)
        :on-close (maybe-invoke-callback on-close session event-value)))))

(defn create-server-endpoint-config
  "Adds a WebSocket endpoint to a ServerContainer.

  The path provides the mapping to the endpoint, and must start with a slash.

  The ws-endpoint-map defines callbacks and configuration for the endpoint.

  When a connection is started for the endpoint, the :on-open callback is invoked; the return value
  is saved as the \"session object\" which is then passed to the remaining callbacks as the first
  function argument.

  :on-open (jakarta.websocket.Session, jakarta.websocket.EndpointConfig)
  : Invoked when client first opens a connection.  The returned value is retained
    and passed as the first argument of the remaining callbacks.

  :on-close (Object, jakarta.websocket.Session, jakarta.websocket.CloseReason)
  : Invoked when the socket is closed, allowing any resources to be freed.

  :on-error (Object, jakarta.websocket.Session, Throwable)
  : Passed any unexpected exceptions.

  :on-text (Object, String)
  : Passed a text message as a single String.

  :on-binary (Object, java.nio.ByteBuffer)
  : Passed a binary message as a single ByteBuffer.

  :configure (jakarta.websocket.server.ServerEndpointConfig.Builder)
  : Called at initialization time to perform any extra configuration of the endpoint (optional)

  :subprotocols (vector of String, optional)
  : Configures the subprotocols of the endpoint

  :idle-timeout-ms (long)
  : Time in milliseconds before an idle websocket is automatically closed.

  All callbacks are optional.  The :on-open callback is critical, as it performs all the one-time setup
  for the WebSocket connection. The [[on-open-start-ws-connection]] function is a good starting place."
  {:since "0.8.0"}
  ^ServerEndpointConfig [^String path ws-endpoint-map]
  (let [callback (make-endpoint-delegate-callback ws-endpoint-map)
        {:keys [subprotocols configure]} ws-endpoint-map
        builder  (ServerEndpointConfig$Builder/create FnEndpoint path)
        _        (do
                   (when subprotocols
                     (.subprotocols builder subprotocols))
                   (when configure
                     (configure builder)))
        config   (.build builder)]
    (.put (.getUserProperties config) FnEndpoint/USER_ATTRIBUTE_KEY callback)
    config))

(defn- servlet-path-parameters
  [path-params]
  (let [result (HashMap.)]
    (doseq [[k v] path-params]
      (.put result (name k) v))
    result))

(defn upgrade-request-to-websocket
  "Upgrades the current request to be a WebSocket connection.

  The core of this invokes [[create-server-endpoint-config]].

  Terminates the interceptor chain, without adding a response.

  Returns the updated context."
  {:added "0.8.0"}
  [context ws-endpoint-map]
  (let [{:keys [^HttpServletRequest servlet-request servlet-response request route]} context
        _               (do
                          (assert servlet-request)
                          (assert servlet-response)
                          (assert route))
        servlet-context (.getServletContext servlet-request)
        container       ^ServerContainer (.getAttribute servlet-context "jakarta.websocket.server.ServerContainer")
        _               (assert container)
        ;; TODO: May need to do some transformation of the :path
        config          (create-server-endpoint-config (:path route)
                                                       ws-endpoint-map)]
    ;; The path-params are included because they are in the Servlet API, and that's normally the only way
    ;; the FnEndpoint class would have access to them, but in a Pedestal app, if they are needed
    ;; they will be in the request map prior to calling upgrade-request-to-websocket.
    (.upgradeHttpToWebSocket container
                             servlet-request
                             servlet-response
                             config
                             (-> request :path-params servlet-path-parameters)))
  (-> context
      servlet-interceptor/disable-response
      chain/terminate))


(defn websocket-upgrade
  "Returns a terminal interceptor for a route that will open a WebSocket connection.

  The ws-endoint-map is passed to [[create-server-endpoint-config]].

  This terminates the interceptor chain without adding a :response key."
  {:added "0.8.0"}
  [ws-endpoint-map]
  (interceptor/interceptor
    {:name  ::websocket-upgrade
     :enter (fn [context]
              (upgrade-request-to-websocket context ws-endpoint-map))}))

(defn add-endpoint
  "Adds a WebSocket endpoint to a ServerContainer.

  The endpoint is constructed around a ServerEndpointConfig creates by
  [[create-server-endpoint-config]].

  Returns nil."
  {:deprecated "0.8.0"}
  [^ServerContainer container ^String path ws-endpoint-map]
  (deprecated `add-endpoint
              (let [config (create-server-endpoint-config path ws-endpoint-map)]
                (.addEndpoint container config)
                nil)))

(defn add-endpoints
  "Adds all websocket endpoints in the path-map."
  {:deprecated "0.8.0"}
  [^ServerContainer container websockets-map]
  (deprecated `add-endpoints
    (doseq [[path endpoint] websockets-map]
      (add-endpoint container path endpoint))))

(defprotocol WebSocketSendAsync
  (ws-send-async [msg ^RemoteEndpoint$Async remote-endpoint]
    "Sends `msg` to `remote-endpoint`. Returns a
     promise channel from which the result can be taken.

     The result is either :success or an Exception."))

(defn- send-handler
  ^SendHandler [chan]
  (reify SendHandler
    (onResult [_ result]
      (if (.isOK result)
        (put! chan :success)
        (put! chan (.getException result))))))

(extend-protocol WebSocketSendAsync
  String
  (ws-send-async [msg ^RemoteEndpoint$Async remote-endpoint]
    (let [p-chan (async/promise-chan)]
      (.sendText remote-endpoint msg (send-handler p-chan))
      p-chan))

  ByteBuffer
  (ws-send-async [msg ^RemoteEndpoint$Async remote-endpoint]
    (let [p-chan (async/promise-chan)]
      (.sendBinary remote-endpoint msg (send-handler p-chan))
      p-chan)))

(defn start-ws-connection
  "Starts a simple websocket connection for the given session and config.

  Returns a channel used to send messages to the client.

  Closing the channel will close the WebSocket session.

  The values written to the channel are either
  a payload (a String, ByteBuffer, or some object
  that satisfies the WebSocketSendAsync protocol) or is a tuple of a payload and a response channel.

  When the response channel is non-nil, the result of the message send is written to it:
  Either the keyword :success, or an Exception thrown when attempting to send the message.

  Options:

  :send-buffer-or-n
  : Used to create the channel, defaults to 10
  "
  [^Session ws-session opts]
  (let [{:keys [send-buffer-or-n]
         :or   {send-buffer-or-n 10}} opts
        send-ch      (async/chan send-buffer-or-n)
        async-remote (.getAsyncRemote ws-session)]
    (go-loop []
      (if-let [payload (and (.isOpen ws-session)
                            (async/<! send-ch))]
        ;; The payload is a message and an optional response channel; a message is either
        ;; a String or a ByteBuffer (or something that implements WebSocketSendAsync).
        (let [[out-msg resp-ch] (if (sequential? payload)
                                  payload
                                  [payload nil])
              ;; TODO: Not really async because we park for the response here.
              result (try (async/<! (ws-send-async out-msg async-remote))
                          (catch Exception ex
                            (log/error :msg "Failed on ws-send-async"
                                       :exception ex)
                            ex))]
          ;; If a resp-ch was provided, then convey the result (e.g., notify the caller
          ;; that the payload was sent).  This result is either :success or an exception.
          (when resp-ch
            (try
              (async/put! resp-ch result)
              (catch Exception ex
                (log/error :msg "Invalid response channel"
                           :exception ex))))
          (recur))
        ;; The session is closed when the channel is closed.
        (.close ws-session)))
    ;; Return the channel used to send messages to the client
    send-ch))

(defn on-open-start-ws-connection
  "Returns an :on-open callback using [[start-ws-connection]] to do the actual work.

  The callback returns the channel used to send messages to the client, which will become
  the first argument passed to the :on-close, :on-error, :on-text, or :on-binary callbacks."
  [opts]
  (fn [^Session ws-session ^EndpointConfig _config]
    (start-ws-connection ws-session opts)))
