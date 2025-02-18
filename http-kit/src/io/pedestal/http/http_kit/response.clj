(ns io.pedestal.http.http-kit.response
  "Utilities for converting Pedestal response :body types to those compatible with Http-Kit."
  (:require [io.pedestal.service.impl :as impl]
            [clojure.core.async :refer [<!!]])
  (:import (clojure.core.async.impl.protocols Channel)
           (clojure.lang Fn IPersistentCollection)
           (java.io File InputStream)
           (java.nio ByteBuffer)
           (java.nio.channels ReadableByteChannel)))

(defprotocol HttpKitResponse

  (convert-response-body [body]
    "Converts a body value to a type compatible with HttpKit."))

(extend-protocol HttpKitResponse

  nil
  (convert-response-body [_] _)

  String
  (convert-response-body [s] s)

  InputStream
  (convert-response-body [stream] stream)

  File
  (convert-response-body [file] file)

  ByteBuffer
  (convert-response-body [buffer]
    (impl/byte-buffer->input-stream buffer))

  ReadableByteChannel
  (convert-response-body [channel]
    (impl/byte-channel->input-stream channel))

  Fn
  (convert-response-body [f]
    (impl/function->input-stream f))

  IPersistentCollection
  (convert-response-body [coll]
    (pr-str coll))

  Channel                                                   ; core.async
  (convert-response-body [ch]
    (convert-response-body (<!! ch))))

(extend (Class/forName "[B")

  HttpKitResponse

  {:convert-response-body identity})
