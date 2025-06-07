; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.http-kit.response
  "Utilities for converting Pedestal response :body types to those compatible with Http-Kit."
  {:added "0.8.0"}
  (:require [io.pedestal.service.impl :as impl]
            [clojure.core.async :refer [<! go]]
            [org.httpkit.server :as hk])
  (:import (clojure.core.async.impl.protocols ReadPort)
           (clojure.lang Fn IPersistentCollection)
           (java.io File InputStream)
           (java.nio ByteBuffer)
           (java.nio.channels ReadableByteChannel)
           (org.httpkit.server AsyncChannel)))

(defprotocol HttpKitResponse

  (convert-response-body [body request]
    "Converts a body value to a type compatible with HttpKit.

    Returns a tuple of the default content type to use (or nil), and the body converted to an acceptible type for Http-Kit."))


(defn- pipe-async-response-channel
  [request response-ch]
  (let [{:keys [async-channel]} request
        committed-ch (:io.pedestal.http.request/response-commited-ch request)]
    (go
      ;; Wait for response to be committed before sending any additional content down.
      (<! committed-ch)
      (loop []
        ;; HttpKit does support most of types supported by Pedestal, with the exception
        ;; of Fn, IPersistentCollection, and ReadableByteChannel.
        (when-let [chunk (<! response-ch)]
          ;; Need to verify that send! is not blocking, or we'll need to add a `thread` call here.
          (hk/send! async-channel chunk false)
          (recur)))
      (hk/close async-channel))
    ;; Return the async channel as the body; this will trigger an initial
    ;; send! of the status/headers, then commited-ch will close, un-parking the loop above.
    async-channel))

(extend-protocol HttpKitResponse

  nil
  (convert-response-body [_ _] [nil nil])

  ;; Pass Http-Kit Async Channel right through; this occurs for SSE or WebSocket requests
  AsyncChannel
  (convert-response-body [ch _] [nil ch])

  String
  (convert-response-body [s _] ["text/plain" s])

  InputStream
  (convert-response-body [stream _] ["application/octet-stream" stream])

  File
  (convert-response-body [file _] ["application/octet-stream" file])

  ByteBuffer
  (convert-response-body [buffer _]
    ["application/octet-stream" (impl/byte-buffer->input-stream buffer)])

  ReadableByteChannel
  (convert-response-body [channel _]
    ["application/octet-stream" (impl/byte-channel->input-stream channel)])

  Fn
  (convert-response-body [f _]
    ["application/octet-stream" (impl/function->input-stream f)])

  IPersistentCollection
  (convert-response-body [coll _]
    ["application/edn" (pr-str coll)])

  ReadPort                                                  ; core.async
  (convert-response-body [response-ch request]
    ["application/octet-stream" (pipe-async-response-channel request response-ch)]))

(extend (Class/forName "[B")

  HttpKitResponse

  {:convert-response-body (fn [byte-array _]
                            ["application/octet-stream" byte-array])})
