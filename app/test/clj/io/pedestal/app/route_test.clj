; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.route-test
  (:require [clojure.test :refer :all]
            [io.pedestal.app.route :refer :all :as r])
  (:use [clojure.core.async :only [go chan <! >! <!! put! alts! alts!! timeout close!]]))

(deftest router-tests
  (testing "one channel and one message"
    (let [cin (chan 10)
          router (router [:test :router] cin)
          cout (chan 10)]
      (put! cin [[[:test :router] :add [cout [:a] :*]]])
      (put! cin [[[:a] :e1]])
      (let [[v c] (alts!! [cout (timeout 1000)])]
        (is (= v [[[:a] :e1]])))
      (close! cin)))

  (testing "one channel two messages"
    (let [cin (chan 10)
          router (router [:test :router] cin)
          cout (chan 10)]
      (put! cin [[[:test :router] :add [cout [:a] :*]]])
      (put! cin [[[:a] :e1] [[:b] :e2]])
      (let [[v c] (alts!! [cout (timeout 1000)])]
        (is (= v [[[:a] :e1]])))
      (close! cin)))

  (testing "two channels two messages"
    (let [cin (chan 10)
          router (router [:test :router] cin)
          cout1 (chan 10)
          cout2 (chan 10)]
      (put! cin [[[:test :router] :add [cout1 [:a] :*]]
                 [[:test :router] :add [cout2 [:b] :*]]])
      (put! cin [[[:a] :e1] [[:b] :e2]])
      (let [[v c] (alts!! [cout1 (timeout 1000)])]
        (is (= v [[[:a] :e1]])))
      (let [[v c] (alts!! [cout2 (timeout 1000)])]
        (is (= v [[[:b] :e2]])))
      (close! cin)))

  (testing "two channels three messages"
    (let [cin (chan 10)
          router (router [:test :router] cin)
          cout1 (chan 10)
          cout2 (chan 10)]
      (put! cin [[[:test :router] :add [cout1 [:a] :*]]])
      (put! cin [[[:test :router] :add [cout2 [:b] :*]]])
      (put! cin [[[:a] :e1] [[:a] :e2] [[:b] :e2]])
      (let [[v c] (alts!! [cout1 (timeout 1000)])]
        (is (= v [[[:a] :e1] [[:a] :e2]])))
      (let [[v c] (alts!! [cout2 (timeout 1000)])]
        (is (= v [[[:b] :e2]])))
      (close! cin)))

  (testing "remove channel"
    (let [cin (chan 10)
          router (router [:test :router] cin)
          cout1 (chan 10)
          cout2 (chan 10)]
      (put! cin [[[:test :router] :add [cout1 [:a] :*]]])
      (put! cin [[[:test :router] :add [cout2 [:b] :*]]])
      (put! cin [[[:a] :e1] [[:a] :e2] [[:b] :e2]])
      (let [[v c] (alts!! [cout1 (timeout 1000)])]
        (is (= v [[[:a] :e1] [[:a] :e2]])))
      (let [[v c] (alts!! [cout2 (timeout 1000)])]
        (is (= v [[[:b] :e2]])))
      (put! cin [[[:test :router] :remove [cout2 [:b] :*]]])
      (put! cin [[[:a] :e1] [[:a] :e2] [[:b] :e2]])
      (let [[v c] (alts!! [cout1 (timeout 1000)])]
        (is (= v [[[:a] :e1] [[:a] :e2]])))
      (let [[v c] (alts!! [cout2 (timeout 1000)])]
        (is (= v nil)))
      (close! cin))))
