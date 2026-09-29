(ns io.pedestal.response-mime
  (:import (clojure.lang Fn IPersistentCollection)
           (java.io File InputStream)
           (java.nio ByteBuffer)
           (java.nio.channels ReadableByteChannel)))

(defprotocol ResponseMime
  (default [this]))

(extend-protocol ResponseMime
  nil (default [_])
  byte/1 (default [_] "application/octet-stream")
  File (default [_] "application/octet-stream")
  InputStream (default [_] "application/octet-stream")
  String (default [_] "text/plain")
  ByteBuffer (default [_] "application/octet-stream")
  Fn (default [_] "application/octet-stream")
  IPersistentCollection (default [_] "application/edn")
  ReadableByteChannel (default [_] "application/octet-stream"))

(def apply-default-content-type
  "An interceptor that will apply a default content type header,
  if none has been supplied, and a default can be identified from
  the response body."
  {:name  ::apply-default-content-type
   :leave (fn [context]
            (let [{:keys [response]} context
                  {:keys [headers body]} response
                  content-type (get headers "Content-Type")
                  default-type (when (nil? content-type)
                                 (default body))]
              (cond-> context
                default-type (assoc-in [:response :headers "Content-Type"] default-type))))})
