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
  {:added "0.8.0"}
  "WebSocket support abstracted away from the Servlet API.")

(defprotocol WebSocketChannel

  "Defines the behavior of an asynchronous WebSocket connection capable of sending and receiving messages."

  (on-open [this callback]
    "Sets up the callback to be passed the channel when the channel is opened.")

  (on-close [this callback]
    "Sets up the callback to be passed the channel and keyword identifying why the channel closed, and a
    native value with more detail.")

  (on-text [this callback]
    "Sets up the callback to be passed the channel and String (a message from the client).")

  (on-binary [this format callback]
    "Sets up the callback to be passed the channel and a binary messages in the indicated format;
    format may be :bytes, :byte-buffer, or :input-stream.")

  (send! [this data]
    "Sends the given data (a String, byte array, InputStream, or ByteBuffer) to the client."))

(defprotocol IntoWebSocketChannel
  "Converts a native value into a WebSocketChannel.  The native value is stored in the
  Ring request map under key :websocket-channel-source."
  (into-websocket-channel [source]))

(defn extract-websocket-channel
  [request]
  (-> request :websocket-channel-source into-websocket-channel))
