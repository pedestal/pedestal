(ns io.pedestal.app.construct-test
  (:require [clojure.test :refer :all]
            [io.pedestal.app.helpers :as helpers]
            [clojure.core.async :refer [go chan put! close! alts!! timeout]]
            [io.pedestal.app.construct :refer :all :as app]))

(defn button-click [_ inform-message]
  [[[[:info :a] inc]]])

(defn flow-b [_ inform-message]
  (let [[_ _ _ model] (first inform-message)]
    [[[[:info :b] (constantly (+ 10 (get-in model [:info :a])))]]]))

(defn output-a [_ inform-message]
  [(mapv (fn [[path event o n]] [[:out :a] event o n]) inform-message)])

(defn output-b [_ inform-message]
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
