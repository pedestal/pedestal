(ns io.pedestal.http.jetty.websockets
  (:require [clojure.core.async :as async :refer [go-loop put!]]
            [io.pedestal.websocket :as pw]
            [io.pedestal.log :as log])
  (:import (jakarta.servlet ServletContext)
           (jakarta.websocket.server ServerContainer)
           (org.eclipse.jetty.servlet ServletContextHandler)
           (org.eclipse.jetty.websocket.core.server WebSocketCreator)
           (jakarta.websocket RemoteEndpoint$Async RemoteEndpoint$Basic SendHandler Session)
           (org.eclipse.jetty.websocket.jakarta.server.config JakartaWebSocketServletContainerInitializer JakartaWebSocketServletContainerInitializer$Configurator)))

;; This is a protocol used to extend the capabilities on messages are
;; marshalled on send
#_
(defprotocol WebSocketSend
  (ws-send [msg remote-endpoint]
    "Sends `msg` to `remote-endpoint`. May block."))

#_(extend-protocol WebSocketSend

    String
    (ws-send [msg ^RemoteEndpoint$Basic remote-endpoint]
      (.sendText remote-endpoint msg))

    ByteBuffer
    (ws-send [msg ^RemoteEndpoint$Basic remote-endpoint]
      (.sendBinary remote-endpoint msg)))

;; Support non-blocking transmission with optional flow control
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

(defn start-ws-connection
  "Given a function of two arguments
  (the Jetty WebSocket Session and its paired core.async 'send' channel),
  and optionally a buffer-or-n for the 'send' channel,
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
           async-remote (.getAsyncRemote ws-session)]
       ;; Let's process sends...
       (go-loop []
         (if-let [out-msg (and (.isOpen ws-session)
                               (async/<! send-ch))]
           (let [ws-send-ch (ws-send-async out-msg async-remote)
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

  The response is either :success or an Exception.
  "
  ([on-connect-fn]
   (start-ws-connection-with-fc-support on-connect-fn 10))
  ([on-connect-fn send-buffer-or-n]
   (fn [^Session ws-session]
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
       (on-connect-fn ws-session send-ch)))))

#_
(defn make-ws-listener
  "Given a map representing WebSocket actions
  (:on-connect, :on-close, :on-error, :on-text, :on-binary),
  return a WebSocketConnectionListener.
  Values for the map are functions with the same arity as the interface."
  [ws-map]
  ;; Ohoh! Endpoint is abstract class - use proxy or proxy+
  (reify
    WebSocketConnectionListener                             ;; Now Endpoint?
    (onWebSocketConnect [this ws-session]
      (when-let [f (:on-connect ws-map)]
        (f ws-session)))
    (onWebSocketClose [this status-code reason]
      (when-let [f (:on-close ws-map)]
        (f status-code reason)))
    (onWebSocketError [this cause]
      (when-let [f (:on-error ws-map)]
        (f cause)))

    WebSocketListener                                       ;; Whazzthis?
    (onWebSocketText [this msg]
      (when-let [f (:on-text ws-map)]
        (f msg)))
    (onWebSocketBinary [this payload offset length]
      (when-let [f (:on-binary ws-map)]
        (f payload offset length)))))

;; JettyWebSocketServerContainer

#_(defn ws-servlet
    "Given a function
    (that takes a ServletUpgradeRequest and ServletUpgradeResponse and returns a WebSocketListener),
    return a WebSocketServlet that uses the function to create new WebSockets (via a factory)."
    [creation-fn]
    (let [creator (reify WebSocketCreator
                    (createWebSocket [this req response]
                      (creation-fn req response)))]
      (proxy [WebSocketServlet] []
        (configure [factory]
          (.setCreator factory creator)))))


(defn add-ws-endpoints
  "XXX"
  #_([^ServletContextHandler ctx ws-paths]
     (add-ws-endpoints ctx ws-paths {:listener-fn (fn [req response ws-map]
                                                    (make-ws-listener ws-map))}))
  ([^ServletContextHandler handler ws-paths]
   (JakartaWebSocketServletContainerInitializer/configure
     handler
     (reify JakartaWebSocketServletContainerInitializer$Configurator

       (^void accept [_ ^ServletContext _context ^ServerContainer server-container]
         (doseq [[path ws-map] ws-paths]
           (pw/add-endpoint server-container path ws-map )))))))

