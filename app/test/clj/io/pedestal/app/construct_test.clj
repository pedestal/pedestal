; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.construct-test
  (:require [clojure.test :refer :all]
            [io.pedestal.app.helpers :as helpers]
            [clojure.core.async :refer [go chan put! close! alts!! timeout]]
            [io.pedestal.app.construct :refer :all :as app]))

(defn button-click [inform-message]
  [[[[:info :a] inc]]])

(defn flow-b [inform-message]
  (let [[_ _ _ model] (first inform-message)]
    [[[[:info :b] (constantly (+ 10 (get-in model [:info :a])))]]]))

(defn output-a [inform-message]
  [(mapv (fn [[path event o n]] [[:out :a] event o n]) inform-message)])

(defn output-b [inform-message]
  [(mapv (fn [[path event o n]] [[:out :b] event o n]) inform-message)])

(deftest app-tests
  (testing "with flow"
    (let [config {:in [[button-click [:button :inc] :click]]
                  :out [[output-a [:info :a] :*]
                        [output-b [:info :b] :*]]
                  :flow [[flow-b [:info :a] :*]]}
          cin (build {:info {:a 0 :b 0}} config)
          cout (chan 10)]
      (put! cin [[[::app/router] :channel-added cout [:out :* :**]]])
      (put! cin [[[:button :inc] :click]])
      (let [x (set (helpers/take-n 2 cout))]
        (is (= x #{[[[:out :a] :updated {:info {:a 0 :b 0}} {:info {:a 1 :b 11}}]]
                   [[[:out :b] :updated {:info {:a 0 :b 0}} {:info {:a 1 :b 11}}]]})))))
  (testing "without flow"
    (let [config {:in [[button-click [:button :inc] :click]]
                  :out [[output-a [:info :a] :*]
                        [output-b [:info :b] :*]]}
          cin (build {:info {:a 0 :b 0}} config)
          cout (chan 10)]
      (put! cin [[[::app/router] :channel-added cout [:out :* :**]]])
      (put! cin [[[:button :inc] :click]])
      (let [x (set (helpers/take-n 1 cout))]
        (is (= x #{[[[:out :a] :updated {:info {:a 0 :b 0}} {:info {:a 1 :b 0}}]]}))))))


;; Calculate square root using Heron's method
;; ================================================================================

(defn abs [x]
  (Math/abs (double x)))

(defn sr-input [[[_ _ {:keys [x guess accuracy] :as init}]]]
  [[[[:info] merge init]]])

(defn divide [[[_ _ _ model]]]
  [[[[:info :divide] (constantly (/ (get-in model [:info :x])
                                    (get-in model [:info :guess])))]]])

(defn sum [[[_ _ _ model]]]
  [[[[:info :sum] (constantly (+ (get-in model [:info :divide])
                                 (get-in model [:info :guess])))]]])

(defn half [[[_ _ _ model]]]
  [[[[:info :half] (constantly (/ (get-in model [:info :sum]) 2))]]])

(defn good-enough? [[[_ _ {{ohalf :half} :info} {{acc :accuracy nhalf :half} :info}]]]
  (when (and ohalf nhalf acc (< acc (abs (- nhalf ohalf))))
    [[[[:info :guess] (constantly (- nhalf acc))]]]))

(defn sr-output [[[_ _ _ model]]]
  [[[[:out] :set-result (get-in model [:info :half])]]])

(deftest square-root-test
  (let [accuracy 0.000001
        config {:in [[sr-input [:user] :start]]
                :flow [[divide [:info :x] :* [:info :guess] :*]
                       [sum [:info :guess] :* [:info :divide] :*]
                       [half [:info :sum] :*]]
                :out [[good-enough? [:info :half] :* [:info :accuracy] :*]
                      [sr-output [:info :half] :*]]}
        ichan (build {:info {:guess 0 :x 0 :accuracy 0 :divide 0 :sum 0 :half 0}} config)
        tchan (chan 10)]
    (put! ichan [[[::app/router] :channel-added tchan [:out :**]]])
    (put! ichan [[[:user] :start {:x 42 :guess 7 :accuracy accuracy}]])
    (let [[[ _ _ answer]] (last (helpers/take-n 4 tchan))]
      (is (<= (abs (- answer 6.48074069840786)) accuracy)))))
