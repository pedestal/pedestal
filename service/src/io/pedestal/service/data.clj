; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.data
  "Utilities for converting to and from specific data types."
  {:added "0.8.0"}
  (:require [clojure.java.io :as io]
            [io.pedestal.service.impl :as impl])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream InputStream)
           (java.nio ByteBuffer)))

(defprotocol ToInputStream

  (->input-stream [value]))

(defprotocol ToByteArray

  (->byte-array [value]))


(extend-protocol ToInputStream

  nil

  (->input-stream [_]
    (ByteArrayInputStream. (byte-array 0)))

  InputStream

  (->input-stream [s] s)

  ByteBuffer

  (->input-stream [buffer]
    (impl/byte-buffer->input-stream buffer)))

(extend-protocol ToByteArray

  nil

  (->byte-array [_] (byte-array 0))

  InputStream

  (->byte-array [s]
    (let [bos (ByteArrayOutputStream. 1000)]
      (io/copy s bos)
      (.toByteArray bos)))

  ByteBuffer

  (->byte-array [buffer]
    (let [result (byte-array (.remaining buffer))]
      (.get buffer result)
      result)))

(extend (Class/forName "[B")

  ToInputStream

  {:->input-stream (fn [byte-array]
                     (ByteArrayInputStream. byte-array))}

  ToByteArray

  {:->byte-array (fn [byte-array] byte-array)})

(def ^:private converters
  {:input-stream ->input-stream
   :byte-array   ->byte-array})

(defn convert
  [format data]
  (let [f (or (get converters format)
              (throw (ex-info (str "unknown format: " format)
                              {:format        format
                               :known-formats (-> converters keys sort)})))]
    (f data)))





