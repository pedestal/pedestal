; Copyright 2024 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.test-test
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.test :refer [parse-url test-servlet-response test-servlet-response-body test-servlet-response-status]]))

(deftest parse-url-test
  (testing "non-root url parses query-string correctly"
    (is (= "param=value"
           (-> "/foo?param=value"
               parse-url
               :query-string))))
  (testing "root url parses query-string correctly"
    (is (= "param=value"
           (-> "/?param=value"
               parse-url
               :query-string))))
  (testing "hosts parse correctly with/without ports"
    (is (= {:scheme nil, :host nil, :port -1, :path "", :query-string nil}
           (parse-url "/")))
    (is (= {:scheme nil, :host nil, :port -1, :path "foo", :query-string nil}
           (parse-url "/foo")))
    (is (= {:scheme nil, :host "localhost", :port -1, :path "", :query-string nil}
           (parse-url "localhost/")))
    (is (= {:scheme nil, :host "localhost", :port -1, :path "foo", :query-string nil}
           (parse-url "localhost/foo")))
    (is (= {:scheme nil, :host "localhost", :port 8080, :path "", :query-string nil}
           (parse-url "localhost:8080/")))
    (is (= {:scheme nil, :host "localhost", :port 8080, :path "foo", :query-string nil}
           (parse-url "localhost:8080/foo")))
    (is (= {:scheme "http", :host "localhost", :port 8080, :path "foo", :query-string nil}
           (parse-url "http://localhost:8080/foo")))
    (is (= {:scheme "http", :host "localhost", :port 8080, :path "foo", :query-string "param=value"}
           (parse-url "http://localhost:8080/foo?param=value")))))

(deftest response-senderror-test
  (testing "sendError with status only"
    (let [resp (test-servlet-response)]
      (.sendError resp 500)
      (is (= 500 (test-servlet-response-status resp)))
      (is (= "Server Error" (.toString (test-servlet-response-body resp))))))
  (testing "sendError with status and message"
    (let [resp (test-servlet-response)]
      (.sendError resp 500 "Boom!")
      (is (= 500 (test-servlet-response-status resp)))
      (is (= "Boom!" (.toString (test-servlet-response-body resp)))))))
