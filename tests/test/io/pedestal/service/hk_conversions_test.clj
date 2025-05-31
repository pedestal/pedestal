; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.hk-conversions-test
  "Tests for conversions from Pedestal's allowed response bodies to the more limited set supported by HttpKit."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [io.pedestal.http.http-kit.response :refer [convert-response-body]])
  (:import (java.io InputStream)
           (java.nio ByteBuffer)
           (java.nio.channels Channels)))

;; A macro, just to ensure proper failure location for reporting inside Cursive
(defmacro assert-conversion
  [expected-default-content-type expected-value body]
  `(let [body# ~body
         [default-content-type# output-body#] (convert-response-body body# nil)]
     (is (= ~expected-default-content-type default-content-type#)
         "mismatch on default content type")
     (is (instance? InputStream output-body#)
         "expected an InputStream result")
     (is (= ~expected-value
            (slurp output-body#))
         "mismatch on content of InputStream result")))

(deftest response-byte-buffer
  (let [s          "Are we not men? We are Devo."
        byte-array (.getBytes s "UTF-8")
        buf        (ByteBuffer/wrap byte-array)]
    (assert-conversion "application/octet-stream" s buf)))

;; Testing for a core.async channel requires something more end-to-end, and is handled
;; with the Server Sent Events tests.

(deftest response-byte-channel
  (let [file    (io/file "file-root/sub/index.html")
        channel (Channels/newChannel (io/input-stream file))]
    (assert-conversion "application/octet-stream" (slurp file) channel)))

(deftest nil-passes-through-unchanged
  (is (= [nil nil]
         (convert-response-body nil nil))))

(deftest input-stream-passes-through-unchanged
  (let [stream (-> "file-root/test.html" io/file io/input-stream)
        [content-type result-stream]  (convert-response-body stream nil)]
    (is (= "application/octet-stream" content-type))
    (is (identical? stream result-stream))))
