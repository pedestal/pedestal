; Copyright 2024 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.response
  "Utilities used to write Ring responses."
  {:added  "0.7.0"
   :no-doc true}
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [cognitect.transit :as transit]
            [ring.util.response :as ring-response])
  (:import (java.io OutputStreamWriter)))

;; Support for things in io.pedestal.http that are deprecated in 0.7.0

(defn print-fn
  [f]
  (fn [output-stream]
    (with-open [writer (OutputStreamWriter. output-stream)]
      (binding [*out* writer]
        (f))
      (.flush writer))))

(defn data-response
  "Given printing function f (which writes to *out*), returns a response wrapped around
  a function that will stream a response to.

  This is sloppy and the corresponding i.o.p.http functions have been deprecated or rewritten."
  [f content-type]
  (ring-response/content-type
    (ring-response/response (print-fn f))
    content-type))

;; Modern stuff

(defn- stream-xform
  [obj writer-fn]
  (fn [output-stream]
    (with-open [writer (io/writer output-stream)]
      (writer-fn obj writer)
      (.flush writer))))

(defn stream-json
  [obj]
  (stream-xform obj json/generate-stream))

(defn stream-transit
  [obj transit-format transit-opts]
  (fn [output-stream]
    (transit/write
      (transit/writer output-stream transit-format transit-opts)
      obj)))
