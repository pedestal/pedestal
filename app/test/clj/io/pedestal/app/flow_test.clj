; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.flow-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [go chan put! close! alts!! timeout]]
            [io.pedestal.app.flow :refer :all :as fl]))

(deftest flow-tests
  (let [inform-c (chan 10)
        data-model {:a 1}
        test-flow (fn [inform-message] [[[[:b] (constantly 42)]]])
        config [[test-flow [:a] :*]]
        transform-c (transform->inform data-model config inform-c)]
    (put! transform-c [[[:a] inc]])
    (let [[v c] (alts!! [inform-c (timeout 1000)])]
      (is (= v [[[:a] :updated {:a 1} {:a 2 :b 42}]
                [[:b] :added {:a 1} {:a 2 :b 42}]])))))
