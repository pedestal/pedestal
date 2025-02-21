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
  (:require [io.pedestal.service.impl :as impl]
            [clojure.core.async :refer [<!!]])
  (:import (clojure.core.async.impl.protocols ReadPort)
           (clojure.lang Fn IPersistentCollection)
           (java.io File InputStream)
           (java.nio ByteBuffer)
           (java.nio.channels ReadableByteChannel)))

(defprotocol HttpKitResponse

  (convert-response-body [body]
    "Converts a body value to a type compatible with HttpKit.

    Returns a tuple of the default content type to use (or nil), and the body converted to an acceptible type for Http-Kit."))

(extend-protocol HttpKitResponse

  nil
  (convert-response-body [_] [nil nil])

  String
  (convert-response-body [s] ["text/plain" s])

  InputStream
  (convert-response-body [stream] ["application/octet-stream" stream])

  File
  (convert-response-body [file] ["application/octet-stream" file])

  ByteBuffer
  (convert-response-body [buffer]
    ["application/octet-stream" (impl/byte-buffer->input-stream buffer)])

  ReadableByteChannel
  (convert-response-body [channel]
    ["application/octet-stream" (impl/byte-channel->input-stream channel)])

  Fn
  (convert-response-body [f]
    ["application/octet-stream" (impl/function->input-stream f)])

  IPersistentCollection
  (convert-response-body [coll]
    ["application/edn" (pr-str coll)])

  ReadPort                                                   ; core.async
  (convert-response-body [ch]
    (convert-response-body (<!! ch))))

(extend (Class/forName "[B")

  HttpKitResponse

  {:convert-response-body (fn [byte-array]
                            ["application/octet-stream" byte-array])})
