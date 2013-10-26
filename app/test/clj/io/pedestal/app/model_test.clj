(ns io.pedestal.app.model-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [go chan put! close! alts!! timeout]]
            [io.pedestal.app.model :refer :all :as model]))

(deftest transform-to-inform-tests
  (testing "message with no args"
    (is (= (first (transform-to-inform {:a 1} [[[:a] inc]]))
           [[[:a] :updated {:a 1} {:a 2}]])))
  (testing "message with one arg"
    (is (= (first (transform-to-inform {:a 1} [[[:a] + 2]]))
           [[[:a] :updated {:a 1} {:a 3}]])))
  (testing "multiple messages"
    (is (= (first (transform-to-inform {:a {:b 1} :c 2} [[[:c] inc] [[:a] dissoc :b]]))
           [[[:c] :updated {:a {:b 1} :c 2} {:a {} :c 3}]
            [[:a :b] :removed {:a {:b 1} :c 2} {:a {} :c 3}]])))
  (testing "multiple messages on the same path"
    (is (= (first (transform-to-inform {:a {:b 1} :c 2} [[[:a] assoc :d 3] [[:a] dissoc :b]]))
           [[[:a :d] :added {:a {:b 1} :c 2} {:a {:d 3} :c 2}]
            [[:a :b] :removed {:a {:b 1} :c 2} {:a {:d 3} :c 2}]])))
  (testing "updates internal keys"
    (let [bump (fn [values x] (reduce (fn [a [k v]]
                                       (if (< v x)
                                         (assoc a k (inc v))
                                         (assoc a k v)))
                                     {}
                                     values))]
      (is (= (first (transform-to-inform {:a {:b 1 :c 5 :d 9 :e 8} :f 2} [[[:a] bump 6]]))
             [[[:a :b] :updated {:a {:c 5, :b 1, :d 9, :e 8}, :f 2} {:a {:e 8, :d 9, :b 2, :c 6}, :f 2}]
              [[:a :c] :updated {:a {:c 5, :b 1, :d 9, :e 8}, :f 2} {:a {:e 8, :d 9, :b 2, :c 6}, :f 2}]]))))
  (testing "removes internal keys"
    (let [clean-up (fn [values] (reduce (fn [a [k v]]
                                         (if (> v 5)
                                           a
                                           (assoc a k v)))
                                       {}
                                       values))]
      (is (= (first (transform-to-inform {:a {:b 1 :d 5 :e 9 :f 8} :c 2} [[[:a] clean-up]]))
             [[[:a :e] :removed {:a {:b 1, :f 8, :d 5, :e 9}, :c 2} {:a {:d 5, :b 1}, :c 2}]
              [[:a :f] :removed {:a {:b 1, :f 8, :d 5, :e 9}, :c 2} {:a {:d 5, :b 1}, :c 2}]])))))

;; Consider discouraging this by adding logging or even throwing an exception
(deftest transform-from-value-to-map-returns-larger-diffs
  (testing "value to map change at [:a :b]"
    (is (= (first (transform-to-inform {:a {:b 1}} [[[:a :b] (constantly {:c {:d 2}})]]))
           [[[:a :b] :updated {:a {:b 1}} {:a {:b {:c {:d 2}}}}]])))
  (testing "map to value change at [:a :b]"
    (is (= (first (transform-to-inform {:a {:b {:c {:d 2}}}} [[[:a :b] (constantly 1)]]))
           [[[:a :b] :updated {:a {:b {:c {:d 2}}}} {:a {:b 1}}]]))))

(deftest transform->inform-tests
  (let [inform-c (chan 10)
        data-model {:a {:b 1} :c 2}
        transform-c (transform->inform data-model inform-c)]
    (put! transform-c [[[:c] inc] [[:a] dissoc :b]])
    (is (= (first (alts!! [inform-c (timeout 1000)]))
           [[[:c] :updated {:a {:b 1}, :c 2} {:a {}, :c 3}]
            [[:a :b] :removed {:a {:b 1}, :c 2} {:a {}, :c 3}]]))
    (close! transform-c))
  (testing "accumulating state"
    (let [inform-c (chan 10)
          data-model {:a {:b 1} :c 2}
          transform-c (transform->inform data-model inform-c)]
      (put! transform-c [[[:c] inc] [[:a] dissoc :b]])
      (is (= (first (alts!! [inform-c (timeout 1000)]))
             [[[:c] :updated {:a {:b 1}, :c 2} {:a {}, :c 3}]
              [[:a :b] :removed {:a {:b 1}, :c 2} {:a {}, :c 3}]]))
      (put! transform-c [[[:c] inc]])
      (is (= (first (alts!! [inform-c (timeout 1000)]))
             [[[:c] :updated {:a {}, :c 3} {:a {}, :c 4}]]))
      (close! transform-c))))

