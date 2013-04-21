; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.data.test.change
  (:use io.pedestal.app.data.change
        clojure.test))

(deftest test-find-changes
  (let [find-changes #'io.pedestal.app.data.change/find-changes]
    (is (= (find-changes {} {} {:a 1} [:a])
           {:added #{[:a]}}))
    (is (= (find-changes {} {:a 1} {:a 2} [:a])
           {:updated #{[:a]}}))
    (is (= (find-changes {} {:a 1} {} [:a])
           {:removed #{[:a]}}))
    (is (= (find-changes {} {:a {:b {}}} {:a {:b {:c 10}}} [:a])
           {:added #{[:a :b :c]}}))
    (is (= (find-changes {} {:a {:b {:c 9}}} {:a {:b {:c 10}}} [:a])
           {:updated #{[:a :b :c]}}))
    (is (= (find-changes {} {:a {:b {:c 9}}} {:a {:b {}}} [:a])
           {:removed #{[:a :b :c]}}))
    (is (= (find-changes {}
                         {:z {:a {:b {:c 9 :d 10}}
                              :g {:k 1}}}
                         {:z {:a {:b {:c 8 :d 10 :g 3}}
                              :g {:p 6}
                              :x 11}}
                         [:z])
           {:added #{[:z :x] [:z :g :p] [:z :a :b :g]}
            :updated #{[:z :a :b :c]}
            :removed #{[:z :g :k]}}))))
