(ns io.pedestal.websocket
  (:require [clojure.core.async :as async :refer [go-loop put!]]
            [io.pedestal.log :as log])
  (:import (io.pedestal.websocket FnEndpoint)
           (jakarta.websocket EndpointConfig SendHandler Session MessageHandler$Whole RemoteEndpoint$Async)
           (jakarta.websocket.server ServerContainer ServerEndpointConfig ServerEndpointConfig$Builder)
           (java.nio ByteBuffer)))

(defn- message-handler
  ^MessageHandler$Whole [f]
  (reify MessageHandler$Whole
    (onMessage [_ message]
      (f message))))

(defn- make-endpoint-callback
  "Given a map defining the WebService behavior, returns a function that will ultimately be used
  by the FnEndpoint instance."
  [ws-map]
  (let [{:keys [on-open
                on-close
                on-error
                on-text                                     ;; TODO: rename to `on-string`?
                on-binary]} ws-map
        maybe-invoke-callback (fn [f & args]
                                (when f
                                  (apply f args)))
        full-on-open (fn [^Session session ^EndpointConfig config]
                       ;; TODO: support adding a pong handler?
                       (when on-text
                         (.addMessageHandler session String (message-handler on-text)))

                       (when on-binary
                         (.addMessageHandler session ByteBuffer (message-handler on-binary)))

                       (maybe-invoke-callback on-open session config))]
    (fn [event-type ^Session session event-value]
             (case event-type
               :on-open (full-on-open session event-value)
               :on-error (maybe-invoke-callback on-error session event-value)
               :on-close (maybe-invoke-callback on-close session event-value)))))

(defn add-endpoint
  [^ServerContainer container ^String path ws-endpoint-map]
  (let [callback (make-endpoint-callback ws-endpoint-map)
        config ^ServerEndpointConfig (-> (ServerEndpointConfig$Builder/create FnEndpoint path)
                                         .build)
        user-properties ^java.util.Map (.getUserProperties config)]
    (.put user-properties FnEndpoint/USER_ATTRIBUTE_KEY callback)
    (.addEndpoint container config)))

(defprotocol WebSocketSendAsync
  (ws-send-async [msg remote-endpoint]
    "Sends `msg` to `remote-endpoint`. Returns a
     promise channel from which the result can be taken."))

(defn- ^SendHandler send-handler
  [chan]
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

(defn on-open-start-ws-connection
  "Given a function of two arguments
  (the Jetty WebSocket Session and its paired core.async 'send' channel),
  and optionally a buffer-or-n for the 'send' channel,
  return a function that can be used as an OnConnect handler.

  Notes:
   - You can control the entire WebSocket Session per client with the
  session object.
   - If you close the `send` channel, Pedestal will close the WS connection."
  ([on-open-fn]
   (on-open-start-ws-connection on-open-fn 10))
  ([on-open-fn send-buffer-or-n]
   (fn [^Session session ^EndpointConfig config]
     (let [send-ch (async/chan send-buffer-or-n)
           async-remote (.getAsyncRemote session)]
       ;; Let's process sends...
       (go-loop []
         (if-let [out-msg (and (.isOpen session)
                               (async/<! send-ch))]
           (let [ws-send-ch (ws-send-async out-msg async-remote)
                 result (async/<! ws-send-ch)]
             (when-not (= :success result)
               (log/error :msg "Failed on ws-send-async"
                          :exception result))
             (recur))
           (.close session)))
       (on-open-fn session config send-ch)))))

(defn on-open-start-ws-fc-connection
  "Like `start-ws-connection` but transmission is non-blocking and supports
  conveying transmission results. This allows services to implement flow
  control.

  Notes:

  Putting a sequential value on the `send` channel signals that a
  transmission response is desired. In this case the value is expected to
  be a 2-tuple of [`msg` `resp-ch`] where `msg` is the message to be sent
  and `resp-ch` is the channel in which the transmission result will be
  put.

  The response is either :success or an Exception.
  "
  ([on-open-fn]
   (on-open-start-ws-fc-connection on-open-fn 10))
  ([on-open-fn send-buffer-or-n]
   (fn [^Session ws-session ^EndpointConfig config]
     (let [send-ch (async/chan send-buffer-or-n)
           async-remote (.getAsyncRemote ws-session)]
       (go-loop []
         (if-let [payload (and (.isOpen ws-session)
                               (async/<! send-ch))]
           (let [[out-msg resp-ch] (if (sequential? payload)
                                     payload
                                     [payload nil])
                 result (try (async/<! (ws-send-async out-msg async-remote))
                             (catch Exception ex
                               (log/error :msg "Failed on ws-send-async"
                                          :exception ex)
                               ex))]
             (when resp-ch
               (try
                 (async/put! resp-ch result)
                 (catch Exception ex
                   (log/error :msg "Invalid response channel"
                              :exception ex))))
             (recur))
           ;; The session closed
           (.close ws-session)))
       (on-open-fn ws-session config send-ch)))))
