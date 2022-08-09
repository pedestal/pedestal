(ns io.pedestal.http.jetty.websockets
  (:require [clojure.core.async :as async :refer [go-loop]]
            [io.pedestal.log :as log])
  (:import (java.nio ByteBuffer)
           (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (org.eclipse.jetty.websocket.server JettyWebSocketCreator
                                                JettyWebSocketServlet)
           (org.eclipse.jetty.websocket.server.config JettyWebSocketServletContainerInitializer)
           (org.eclipse.jetty.websocket.api Session
                                            WebSocketListener
                                            WebSocketConnectionListener
                                            RemoteEndpoint
                                            WriteCallback)))


;; This is a protocol used to extend the capabilities on messages are
;; marshalled on send
(defprotocol WebSocketSend
  (ws-send [msg remote-endpoint]
    "Sends `msg` to `remote-endpoint`. May block."))

(extend-protocol WebSocketSend

  String
  (ws-send [msg ^RemoteEndpoint remote-endpoint]
    (.sendString remote-endpoint msg))

  ByteBuffer
  (ws-send [msg ^RemoteEndpoint remote-endpoint]
    (.sendBytes remote-endpoint msg)))

(deftype ChannelWriteCallback [resp-chan]
  WriteCallback
  (writeFailed [_ ex]
    (async/put! resp-chan ex))
  (writeSuccess [_]
    (async/put! resp-chan :success)))

;; Support non-blocking transmission with optional flow control
(defprotocol WebSocketSendAsync
  (ws-send-async [msg remote-endpoint]
    "Sends `msg` to `remote-endpoint`. Returns a
     promise channel from which the result can be taken."))

(extend-protocol WebSocketSendAsync
  String
  (ws-send-async [msg ^RemoteEndpoint remote-endpoint]
    (let [p-chan (async/promise-chan)]
      (.sendString remote-endpoint msg (->ChannelWriteCallback p-chan))
      p-chan))

  ByteBuffer
  (ws-send-async [msg ^RemoteEndpoint remote-endpoint]
    (let [p-chan (async/promise-chan)]
      (.sendBytes remote-endpoint msg (->ChannelWriteCallback p-chan))
      p-chan)))

(defn start-ws-connection
  "Given a function of two arguments
  (the Jetty WebSocket Session and its paired core.async 'send' channel),
  and optionall a buffer-or-n for the 'send' channel,
  return a function that can be used as an OnConnect handler.

  Notes:
   - You can control the entire WebSocket Session per client with the
  session object.
   - If you close the `send` channel, Pedestal will close the WS connection."
  ([on-connect-fn]
   (start-ws-connection on-connect-fn 10))
  ([on-connect-fn send-buffer-or-n]
   (fn [^Session ws-session]
     (let [send-ch (async/chan send-buffer-or-n)
           remote  ^RemoteEndpoint (.getRemote ws-session)]
       ;; Let's process sends...
       (go-loop []
         (if-let [out-msg (and (.isOpen ws-session)
                               (async/<! send-ch))]
           (let [ws-send-ch (ws-send-async out-msg remote)
                 result (async/<! ws-send-ch)]
             (when-not (= :success result)
               (log/error :msg "Failed on ws-send-async"
                          :exception result))
             (recur))
           (.close ws-session)))
       (on-connect-fn ws-session send-ch)))))

(defn start-ws-connection-with-fc-support
  "Like `start-ws-connection` but transmission is non-blocking and supports
  conveying transmission results. This allows services to implement flow
  control.

  Notes:

  Putting a sequential value on the `send` channel signals that a
  transmission response is desired. In this case the value is expected to
  be a 2-tuple of [`msg` `resp-ch`] where `msg` is the message to be sent
  and `resp-ch` is the channel in which the transmission result will be
  put.
  "
  ([on-connect-fn]
   (start-ws-connection-with-fc-support on-connect-fn 10))
  ([on-connect-fn send-buffer-or-n]
   (fn [^Session ws-session]
     (let [send-ch (async/chan send-buffer-or-n)
           remote  ^RemoteEndpoint (.getRemote ws-session)]
       (go-loop []
         (if-let [payload (and (.isOpen ws-session)
                               (async/<! send-ch))]
           (let [[out-msg resp-ch] (if (sequential? payload)
                                     payload
                                     [payload nil])
                 result (try (async/<! (ws-send-async out-msg remote))
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
           (.close ws-session)))
       (on-connect-fn ws-session send-ch)))))

(defn make-ws-listener
  "Given a map representing WebSocket actions
  (:on-connect, :on-close, :on-error, :on-text, :on-binary),
  return a WebSocketConnectionListener.
  Values for the map are functions with the same arity as the interface."
  [ws-map]
  (reify
    WebSocketConnectionListener
    (onWebSocketConnect [this ws-session]
      (when-let [f (:on-connect ws-map)]
        (f ws-session)))
    (onWebSocketClose [this status-code reason]
      (when-let [f (:on-close ws-map)]
        (f status-code reason)))
    (onWebSocketError [this cause]
      (when-let [f (:on-error ws-map)]
        (f cause)))

    WebSocketListener
    (onWebSocketText [this msg]
      (when-let [f (:on-text ws-map)]
        (f msg)))
    (onWebSocketBinary [this payload offset length]
      (when-let [f (:on-binary ws-map)]
        (f payload offset length)))))

(defn ws-servlet
  "Given a function
  (that takes a ServletUpgradeRequest and ServletUpgradeResponse and returns a WebSocketListener),
  return a JettyWebSocketServlet that uses the function to create new WebSockets (via a factory)."
  ^JettyWebSocketServlet [creation-fn]
  (let [creator (reify JettyWebSocketCreator
                  (createWebSocket [this req response]
                    (creation-fn req response)))]
    (proxy [JettyWebSocketServlet] []
      (configure [factory]
        (.setCreator factory creator)))))

(defn add-ws-endpoints
  "Given a ServletContextHandler and a map of WebSocket (String) paths to action maps,
  produce corresponding Servlets per path and add them to the context.
  Return the context when complete.

  You may optionally also pass in a map of options.
  Currently supported options:
   :listener-fn - A function of 3 args,
                  the ServletUpgradeRequest, ServletUpgradeResponse, and the WS-Map
                  that returns a WebSocketListener."
  ([^ServletContextHandler ctx ws-paths]
   (add-ws-endpoints ctx ws-paths {:listener-fn (fn [req response ws-map]
                                                  (make-ws-listener ws-map))}))
  ([^ServletContextHandler ctx ws-paths opts]
   (let [{:keys [listener-fn]
          :or {listener-fn (fn [req response ws-map]
                             (make-ws-listener ws-map))}} opts]
     (doseq [[^String path ws-map] ws-paths]
       (let [servlet (ws-servlet (fn [req response]
                                   (listener-fn req response ws-map)))]
         (.addServlet ctx (ServletHolder. ^javax.servlet.Servlet servlet) path)))
     (JettyWebSocketServletContainerInitializer/configure ctx nil)
     ctx)))

