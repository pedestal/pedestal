; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.websocket
  "WebSocket support abstracted away from the Servlet API."
  {:added "0.8.0"}
  (:require [clojure.core.async :refer [go-loop thread chan <! put!]]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.log :as log]))

(defprotocol WebSocketChannel

  "Defines the behavior of an asynchronous WebSocket connection capable of sending and receiving messages."

  (on-text [this callback]
    "Sets up a callback for received text messages.

    The callback receives the channel, the process object, and message.
    The return value is ignored.

    May only be called once. Returns nil.")

  (on-binary [this format callback]
    "Sets up a callback for received binary messages.

    The format may be :bytes, :byte-buffer, or :input-stream.

    The callback receives the channel, the process object, and the binary message (in the chosen format).
    The return value is ignored.

    May only be called once.  Returns nil.")

  (send-text! [this ^String string]
    "Sends the given string to the channel as a text frame.

    Returns true on success, false if the channel has closed.")

  (send-binary! [this data]
    "Sends the given binary data to the channel as either a byte array or an InputStream.

    Returns true on success, false if the channel has closed.")

  (close! [this]
    "Closes the channel, preventing further sends or receives.  Returns nil."))

(defprotocol InitializeWebSocket
  "Converts a native value (supplied by the network connector) into a WebSocketChannel.
  Expects the native value to be stored in the
  Ring request map under key :websocket-channel-source."

  (initialize-websocket [source context ws-opts]
    "Passed the native channel (specific to the network connector), the context, and options.

    Performs initialization to initialize a WebSocketChannel,
    and performs any modification to the context needed,
    including setting the :websocket-channel key to the WebSocketChannel instance."))

(defn upgrade-request-to-websocket
  "Initializes a WebSocketChannel for the request stored in the context.

  Returns a modified context map.

  ws-opts:

  :on-open - callback passed the WebSocketChannel and the request map, returns a process object.

  :on-close - callback passed the WebSocketChannel, the process object, and the close reason; return value is ignored.

  The close reason is a keyword, but the values vary slightly between network connectors.

  :on-text callback passed the WebSocketChannel, the process object, and a String; return value is ignored.

  :on-binary callback passed the WebSocketChannel, the process object, and the binary data (as a ByteBuffer); return value is ignored.

  Note that in order to override the type of binary data passed to the callback, you must make use of [[on-binary]] from
  your :on-open callback."
  [context ws-opts]
  ;; The Pedestal Connector is responsible for putting this key into the context:
  (initialize-websocket (:websocket-channel-source context) context ws-opts))

(defn websocket-interceptor
  "Creates and returns an interceptor as a wrapper around [[upgrade-request-to-websocket]]."
  [interceptor-name ws-opts]
  (interceptor
    {:name  interceptor-name
     :enter (fn [context]
              (upgrade-request-to-websocket context ws-opts))}))

(defn- async-send!
  [channel message]
  (thread
    (try
      (let [result (if (string? message)
                     (send-text! channel message)
                     (send-binary! channel message))]
        (if result
          :success
          :closed))
      (catch Exception e
        e))))

(defn start-ws-connection
  "Starts a simple WebSocket connection around a WebSocketChannel. The connection allows
  the server to asyncronously send messages to the client.

  Returns a core.async channel used to send messages to the client.

  Closing the channel will close the WebSocketChannel.

  Writing a value to the channel will send a message to the client.  A value is either the message
  (as a String, ByteBuffer, InputStream, or byte array) or a tuple of the message and a response channel.

  When the response channel is provided, the result of sending the message is written to it:
  Either the keyword :success, or an Exception thrown when attempting to send the message.

  Message delivery is sequential, not parallel.

  Options:

  :send-buffer-or-n
  : Used to create the channel, defaults to 10
  "
  [ws-channel opts]
  (let [{:keys [send-buffer-or-n]
         :or   {send-buffer-or-n 10}} opts
        send-ch (chan send-buffer-or-n)]
    (go-loop []
      (if-let [payload (<! send-ch)]
        (let [[message resp-ch] (if (sequential? payload)
                                  payload
                                  [payload nil])
              result (<! (async-send! ws-channel message))]
          ;; If a resp-ch was provided, then convey the result (e.g., notify the caller
          ;; that the payload was sent).  This result is either :success, :closed, or an exception.
          (when resp-ch
            (try
              (put! resp-ch result)
              (catch Exception ex
                (log/error :msg "Invalid response channel"
                           :exception ex))))
          (when (not= :closed result)
            (recur)))
        ;; The session is closed when the channel is closed.
        (close! ws-channel)))
    ;; Return the channel used to send messages to the client
    send-ch))


