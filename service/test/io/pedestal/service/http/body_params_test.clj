; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.service.http.body-params-test
  (:use io.pedestal.service.http.body-params
        clojure.pprint
        clojure.test
        clojure.repl
        clojure.tools.namespace.repl)
  (:require [io.pedestal.service.impl.interceptor :as interceptor]))

(defn as-context [content-type ^String body]
  (let [body-reader (java.io.ByteArrayInputStream. (.getBytes body))]
    {:request {:content-type content-type
               :body body-reader}}))

(def i (:enter (body-params)))

(def json-context (as-context "application/json" "{ \"foo\": \"BAR\"}"))

(deftest parses-json
  (let [new-context (i json-context)
        new-request (:request new-context)]
    (is (= (:json-params new-request) {"foo" "BAR"}))))


(def form-context (as-context  "application/x-www-form-urlencoded" "foo=BAR"))

(deftest parses-form-data
    (let [new-context (i form-context)
          new-request (:request new-context)]
    (is (= (:form-params new-request) {"foo" "BAR"}))))


(def edn-context ( as-context "application/edn" "(i wish i [was in] eden)"))

(deftest parses-edn
    (let [new-context (i edn-context)
          new-request (:request new-context)]
    (is (= (:edn-params new-request) '(i wish i [was in] eden)))))

(def edn-context-with-eval (as-context "application/edn" "#=(eval (println 1234)"))

(deftest throws-an-error-if-eval-in-edn
  (is (thrown? Exception (i edn-context-with-eval))))

;; Translation: "Today is a good day to die."
(def klingon "Heghlu'meH QaQ jajvam")
(def unknown-content-type-context (as-context "application/klingon" klingon))

(deftest unknown-content-type-does-nothing
    (let [new-context (i unknown-content-type-context)
          new-request (:request new-context)]
      (is (=  (slurp (:body new-request)) klingon))))

(def nil-content-type-context (as-context nil klingon))

(deftest nil-content-type-does-nothing
    (let [new-context (i nil-content-type-context)
          new-request (:request new-context)]
      (is (=  (slurp (:body new-request)) klingon))))
