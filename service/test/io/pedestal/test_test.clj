; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.test-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer :all]))

(deftest parse-url-test
  (testing "non-root url parses query-string correctly"
    (is (=
         "param=value"
         (-> "/foo?param=value"
             parse-url
             :query-string))))
  (testing "root url parses query-string correctly"
    (is (=
         "param=value"
         (-> "/?param=value"
             parse-url
             :query-string)))))
