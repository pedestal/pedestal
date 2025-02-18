; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.conversions-test
  "Tests for the conversions that occur inside [[io.pedestal.service.test]]."
  (:require [clojure.edn :as edn]
            [clojure.core.async :refer [go]]
            [clojure.java.io :as io]
            [io.pedestal.service.test :as test]
            [clojure.test :refer [deftest is]])
  (:import (java.io InputStream)
           (java.nio ByteBuffer)
           (java.nio.channels Channels)))

(deftest request-input-stream-is-unchanged
  (let [input-stream (-> "pedestal-config.edn" io/resource io/input-stream)]
    (is (identical? input-stream
                    (test/convert-request-body input-stream)))))

(deftest request-file-is-converted-to-stream
  (let [file   (io/file "file-root/index.html")
        stream (test/convert-request-body file)]
    (is (instance? InputStream stream))
    (is (= (slurp file)
           (slurp stream)))))

(deftest request-nil-is-nil
  (is (nil? (test/convert-request-body nil))))

(deftest request-string-is-converted-to-stream
  (let [body   "A mind forever voyaging"
        stream (test/convert-request-body body)]
    (is (instance? InputStream stream))
    (is (= body
           (slurp stream)))))

(deftest response-input-stream-is-unchanged
  (let [input-stream (-> "pedestal-config.edn" io/resource io/input-stream)]
    (is (identical? input-stream
                    (test/convert-response-body input-stream)))))

(deftest response-nil-is-nil
  (is (nil? (test/convert-response-body nil))))

(deftest response-file-is-converted-to-stream
  (let [file   (io/file "file-root/index.html")
        stream (test/convert-response-body file)]
    (is (instance? InputStream stream))
    (is (= (slurp file)
           (slurp stream)))))

(deftest response-edn
  (let [body   {:this [:and :that]}
        stream (test/convert-response-body body)]
    (is (instance? InputStream stream))
    (is (= body
           (-> stream slurp edn/read-string)))))

(deftest response-fn
  (let [file   (io/file "file-root/test.html")
        f      (fn [output-stream]
                 (io/copy file output-stream))
        stream (test/convert-response-body f)]
    (is (instance? InputStream stream))
    (is (= (slurp file)
           (slurp stream)))))

(deftest response-byte-buffer
  (let [s          "Duty now for the future"
        byte-array (.getBytes s "UTF-8")
        buf        (ByteBuffer/wrap byte-array)
        stream     (test/convert-response-body buf)]
    (is (instance? InputStream stream))
    (is (= s
           (slurp stream)))))

(deftest response-async-channel
  (let [s      "choose immutability, and see where it leads you"
        ch     (go s)
        stream (test/convert-response-body ch)]
    (is (instance? InputStream stream))
    (is (= s
           (slurp stream)))))

(deftest response-byte-channel
  (let [file (io/file "file-root/sub/index.html")
        channel (Channels/newChannel (io/input-stream file))
        stream (test/convert-response-body channel)]
    (is (instance? InputStream stream))
    (is (= (slurp file)
           (slurp stream)))))




