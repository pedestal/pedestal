(ns io.pedestal.http.jetty.websockets
  (:require [io.pedestal.websocket :as pw])
  (:import (jakarta.servlet ServletContext)
           (jakarta.websocket.server ServerContainer)
           (org.eclipse.jetty.servlet ServletContextHandler)
           (org.eclipse.jetty.websocket.jakarta.server.config JakartaWebSocketServletContainerInitializer JakartaWebSocketServletContainerInitializer$Configurator)))

;; This is a protocol used to extend the capabilities on messages are
;; marshalled on send

;(defprotocol WebSocketSend
;  (ws-send [msg remote-endpoint]
;    "Sends `msg` to `remote-endpoint`. May block."))
;
;(extend-protocol WebSocketSend
;
;    String
;    (ws-send [msg ^RemoteEndpoint$Basic remote-endpoint]
;      (.sendText remote-endpoint msg))
;
;    ByteBuffer
;    (ws-send [msg ^RemoteEndpoint$Basic remote-endpoint]
;      (.sendBinary remote-endpoint msg)))

;; Support non-blocking transmission with optional flow control


;(defn make-ws-listener
;  "Given a map representing WebSocket actions
;  (:on-connect, :on-close, :on-error, :on-text, :on-binary),
;  return a WebSocketConnectionListener.
;  Values for the map are functions with the same arity as the interface."
;  [ws-map]
;  ;; Ohoh! Endpoint is abstract class - use proxy or proxy+
;  (reify
;    WebSocketConnectionListener                             ;; Now Endpoint?
;    (onWebSocketConnect [this ws-session]
;      (when-let [f (:on-connect ws-map)]
;        (f ws-session)))
;    (onWebSocketClose [this status-code reason]
;      (when-let [f (:on-close ws-map)]
;        (f status-code reason)))
;    (onWebSocketError [this cause]
;      (when-let [f (:on-error ws-map)]
;        (f cause)))
;
;    WebSocketListener                                       ;; Whazzthis?
;    (onWebSocketText [this msg]
;      (when-let [f (:on-text ws-map)]
;        (f msg)))
;    (onWebSocketBinary [this payload offset length]
;      (when-let [f (:on-binary ws-map)]
;        (f payload offset length)))))


;; JettyWebSocketServerContainer

;(defn ws-servlet
;    "Given a function
;    (that takes a ServletUpgradeRequest and ServletUpgradeResponse and returns a WebSocketListener),
;    return a WebSocketServlet that uses the function to create new WebSockets (via a factory)."
;    [creation-fn]
;    (let [creator (reify WebSocketCreator
;                    (createWebSocket [this req response]
;                      (creation-fn req response)))]
;      (proxy [WebSocketServlet] []
;        (configure [factory]
;          (.setCreator factory creator)))))

;; TODO: fiddle this so that it goes in io.pedestal.websocket with a protocol or
;; something implemented for Jetty.

(defn add-ws-endpoints
  "Add WebSocket endpoints to the handler.

  Endpoints are defined in terms of a map of path to endpoint map.

  Each endpoint map contains some number of the following keys, each of
  which (if present) is a callback function.

  :on-open (jakarta.websocket.Session,  jakarta.websocket.EndpointConfig)
  : Invoked when client first opens a connection.

  :on-close (jakarta.websocket.Session, jakarta.websocket.CloseReason)
  : Invoked when the socket is closed, allowing any resources to be freed.

  :on-error (jakarta.websocket.Session, Throwable)
  : Passed any unexpected exceptions.

  :on-text (String)
  : Passed a text message as a single String.

  :on-binary (java.nio.ByteBuffer)
  : Passed a binary message as a single ByteBuffer.


  Commonly,:on-open is provided by a call to [[on-open-start-ws-connection]] or
  [[on-open-start-ws-fc-connection]].
  "
  [^ServletContextHandler handler ws-paths]
  (JakartaWebSocketServletContainerInitializer/configure
    handler
    (reify JakartaWebSocketServletContainerInitializer$Configurator

      (^void accept [_ ^ServletContext _context ^ServerContainer server-container]
        (doseq [[path ws-map] ws-paths]
          (pw/add-endpoint server-container path ws-map))))))

