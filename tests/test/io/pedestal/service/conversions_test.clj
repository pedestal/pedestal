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
  "Tests for the conversions that occur inside [[io.pedestal.connector.test]]
  and [[io.pedestal.service.data]]."
  (:require [clojure.edn :as edn]
            [clojure.core.async :refer [go]]
            [clojure.java.io :as io]
            [io.pedestal.connector.test :as test]
            [io.pedestal.service.data :as data :refer [convert]]
            [clojure.test :refer [deftest is]])
  (:import (java.io ByteArrayInputStream InputStream)
           (java.nio ByteBuffer)
           (java.nio.channels Channels)
           (javassist.bytecode ByteArray)))

(deftest request-input-stream-is-unchanged
  (let [input-stream (-> "pedestal-config.edn" io/resource io/input-stream)]
    (is (identical? input-stream
                    (test/coerce-request-body input-stream)))))

(deftest request-file-is-converted-to-stream
  (let [file   (io/file "file-root/index.html")
        stream (test/coerce-request-body file)]
    (is (instance? InputStream stream))
    (is (= (slurp file)
           (slurp stream)))))

(deftest request-nil-is-nil
  (is (nil? (test/coerce-request-body nil))))

(deftest request-string-is-converted-to-stream
  (let [body   "A mind forever voyaging"
        stream (test/coerce-request-body body)]
    (is (instance? InputStream stream))
    (is (= body
           (slurp stream)))))

(deftest response-input-stream-is-unchanged
  (let [input-stream (-> "pedestal-config.edn" io/resource io/input-stream)]
    (is (identical? input-stream
                    (test/coerce-response-body input-stream)))))

(deftest response-nil-is-nil
  (is (nil? (test/coerce-response-body nil))))

(deftest response-file-is-converted-to-stream
  (let [file   (io/file "file-root/index.html")
        stream (test/coerce-response-body file)]
    (is (instance? InputStream stream))
    (is (= (slurp file)
           (slurp stream)))))

(deftest response-edn
  (let [body   {:this [:and :that]}
        stream (test/coerce-response-body body)]
    (is (instance? InputStream stream))
    (is (= body
           (-> stream slurp edn/read-string)))))

(deftest response-fn
  (let [file   (io/file "file-root/test.html")
        f      (fn [output-stream]
                 (io/copy file output-stream))
        stream (test/coerce-response-body f)]
    (is (instance? InputStream stream))
    (is (= (slurp file)
           (slurp stream)))))

(deftest response-byte-buffer
  (let [s          "Duty now for the future"
        byte-array (.getBytes s "UTF-8")
        buf        (ByteBuffer/wrap byte-array)
        stream     (test/coerce-response-body buf)]
    (is (instance? InputStream stream))
    (is (= s
           (slurp stream)))))

(deftest response-async-channel
  (let [s      "choose immutability, and see where it leads you"
        ch     (go s)
        stream (test/coerce-response-body ch)]
    (is (instance? InputStream stream))
    (is (= s
           (slurp stream)))))

(deftest response-byte-channel
  (let [file    (io/file "file-root/sub/index.html")
        channel (Channels/newChannel (io/input-stream file))
        stream  (test/coerce-response-body channel)]
    (is (instance? InputStream stream))
    (is (= (slurp file)
           (slurp stream)))))


(deftest data-nil-to-byte-array
  (let [array (convert :byte-array nil)]
    (is (= (Class/forName "[B")
           (type array)))))

(deftest data-nil-to-byte-buffer
  (let [buf (convert :byte-buffer nil)]
    (is (instance? ByteBuffer buf))
    (is (= 0
           (.limit buf)))))


(deftest data-input-stream-byte-buffer
  (let [content "This is some content."
        stream  (convert :input-stream (.getBytes content "UTF-8"))
        buf     (convert :byte-buffer stream)]
    (is (= content
           (slurp (convert :input-stream buf))))))

(deftest data-byte-buffer-to-byte-array
  (let [content "we'll push this through the pipe"
        result (->> (.getBytes content "UTF-8")
                    (convert :byte-buffer)
                    (convert :byte-array))]
    (is (= content
           (slurp result)))))


(deftest unknown-data-conversion
  (when-let [e (is (thrown? Exception
                            (convert :pipe nil)))]
    (is (= "unknown format: :pipe" (ex-message e)))
    (is (= {:format        :pipe
            :known-formats [:byte-array :byte-buffer :input-stream]}
           (ex-data e)))))




