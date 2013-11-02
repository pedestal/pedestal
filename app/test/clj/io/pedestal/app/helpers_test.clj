; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.helpers-test
  " Sanity check test helpers"
  (:require [clojure.test :refer :all]
            [io.pedestal.app.helpers :refer :all])
  (:use [clojure.core.async :only [go chan <! >! <!! put! alts! alts!! timeout close!]]))

(deftest ideal-change-report-tests
  (let [o {} n {:a 1}]
    (is (= (ideal-change-report o n)
           #{[[:a] :added o n]})))
  (let [o {:a 0} n {:a 1}]
    (is (= (ideal-change-report o n)
           #{[[:a] :updated o n]})))
  (let [o {:a 0} n {}]
    (is (= (ideal-change-report o n)
           #{[[:a] :removed o n]})))
  (let [o {:a 0 :b 0 :c {:d 0 :e {:f {:h 0} :g {:i 0}}}}
        n {:a 0 :b 0 :c {:d 0 :e {:f {:h 0} :g {:i 1}}}}]
    (is (= (ideal-change-report o n)
           #{[[:c :e :g :i] :updated o n]})))
  (let [o {:a 0 :b 0 :c {:d 0 :e 0}} n {:b 2 :c {:d 3 :f 0}}]
    (is (= (ideal-change-report o n)
           #{[[:a] :removed o n]
             [[:b] :updated o n]
             [[:c :d] :updated o n]
             [[:c :e] :removed o n]
             [[:c :f] :added o n]}))))
