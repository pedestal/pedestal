(ns io.pedestal.app.model-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [go chan put! close! alts!! timeout]]
            [simple-check.core :as sc]
            [simple-check.generators :as gen]
            [simple-check.properties :as prop]
            [simple-check.clojure-test :as ct :refer (defspec)]
            [io.pedestal.app.generators :as pgen]
            [io.pedestal.app.helpers :refer :all]
            [io.pedestal.app.model :refer :all :as model]))

(deftest transform-to-inform-tests
  (testing "start with empty model"
    (is (= (set (first (transform-to-inform {} [[[:a] assoc :x 1]])))
           (ideal-change-report {} {:a {:x 1}}))))
  (testing "message with no args"
    (is (= (set (first (transform-to-inform {:a 1} [[[:a] inc]])))
           (ideal-change-report {:a 1} {:a 2}))))
  (testing "message with one arg"
    (is (= (set (first (transform-to-inform {:a 1} [[[:a] + 2]])))
           (ideal-change-report {:a 1} {:a 3}))))
  (testing "multiple messages"
    (is (= (set (first (transform-to-inform {:a {:b 1} :c 2} [[[:c] inc] [[:a] dissoc :b]])))
           (ideal-change-report {:a {:b 1} :c 2} {:a {} :c 3}))))
  (testing "multiple messages on the same path"
    (is (= (set (first (transform-to-inform {:a {:b 1} :c 2} [[[:a] assoc :d 3] [[:a] dissoc :b]])))
           (ideal-change-report {:a {:b 1} :c 2} {:a {:d 3} :c 2}))))
  (testing "updates internal keys"
    (let [bump (fn [values x] (reduce (fn [a [k v]]
                                       (if (< v x)
                                         (assoc a k (inc v))
                                         (assoc a k v)))
                                     {}
                                     values))]
      (is (= (set (first (transform-to-inform {:a {:b 1 :c 5 :d 9 :e 8} :f 2} [[[:a] bump 6]])))
             (ideal-change-report {:a {:c 5, :b 1, :d 9, :e 8}, :f 2}
                                  {:a {:e 8, :d 9, :b 2, :c 6}, :f 2})))))
  (testing "removes internal keys"
    (let [clean-up (fn [values] (reduce (fn [a [k v]]
                                         (if (> v 5)
                                           a
                                           (assoc a k v)))
                                       {}
                                       values))]
      (is (= (set (first (transform-to-inform {:a {:b 1 :d 5 :e 9 :f 8} :c 2} [[[:a] clean-up]])))
             (ideal-change-report {:a {:b 1, :f 8, :d 5, :e 9}, :c 2} {:a {:d 5, :b 1}, :c 2})))))
  (testing "different transforms produce same results"
    (is (= (set (first (transform-to-inform {:a {:b 0 :c 0 :d 0}} [[[:a] (constantly {:b 1 :c 1 :d 1})]])))
           (set (first (transform-to-inform {:a {:b 0 :c 0 :d 0}} [[[:a] assoc :b 1]
                                                                   [[:a] assoc :c 1]
                                                                   [[:a] assoc :d 1]])))
           (set (first (transform-to-inform {:a {:b 0 :c 0 :d 0}} [[[:a :b] inc]
                                                                   [[:a :c] inc]
                                                                   [[:a :d] inc]])))
           (ideal-change-report {:a {:b 0 :c 0 :d 0}} {:a {:b 1 :c 1 :d 1}}))))
  (testing "replace a sub-map"
    (is (= (set (first (transform-to-inform {:a {:b {:c 0 :d 0}}}
                                            [[[:a] (constantly {:b {:c 0 :d 1}})]])))
           (ideal-change-report {:a {:b {:c 0 :d 0}}} {:a {:b {:c 0 :d 1}}})))))

;; Consider discouraging this by adding logging or even throwing an exception
(deftest transform-from-value-to-map-returns-larger-diffs
  (testing "value to map change at [:a :b]"
    (is (= (set (first (transform-to-inform {:a {:b 1}} [[[:a :b] (constantly {:c {:d 2}})]])))
           (ideal-change-report {:a {:b 1}} {:a {:b {:c {:d 2}}}}))))
  (testing "map to value change at [:a :b]"
    (is (= (set (first (transform-to-inform {:a {:b {:c {:d 2}}}} [[[:a :b] (constantly 1)]])))
           (ideal-change-report {:a {:b {:c {:d 2}}}} {:a {:b 1}})))))

(deftest transform->inform-tests
  (let [inform-c (chan 10)
        data-model {:a {:b 1} :c 2}
        transform-c (transform->inform data-model inform-c)]
    (put! transform-c [[[:c] inc] [[:a] dissoc :b]])
    (is (= (set (first (alts!! [inform-c (timeout 1000)])))
           (ideal-change-report {:a {:b 1} :c 2} {:a {} :c 3})))
    (close! transform-c))
  (testing "accumulating state"
    (let [inform-c (chan 10)
          data-model {:a {:b 1} :c 2}
          transform-c (transform->inform data-model inform-c)]
      (put! transform-c [[[:c] inc] [[:a] dissoc :b]])
      (is (= (set (first (alts!! [inform-c (timeout 1000)])))
             (ideal-change-report {:a {:b 1} :c 2} {:a {} :c 3})))
      (put! transform-c [[[:c] inc]])
      (is (= (set (first (alts!! [inform-c (timeout 1000)])))
             (ideal-change-report {:a {} :c 3} {:a {} :c 4})))
      (close! transform-c))))


;; simple-check tests
;; --------------------------------------------------------------------------------

(defn assoc-ok [m path k v]
  (or (not (map? (get-in m path))) ;; this would be a user error
      (= (set (first (transform-to-inform m [[path assoc k v]])))
         (ideal-change-report m (update-in m path assoc k v)))))

(defspec assoc-model-tests
  50
  (prop/for-all [m (gen/sized pgen/model)
                 path (gen/such-that not-empty (gen/vector gen/keyword))
                 k gen/keyword
                 i (gen/one-of [gen/int (gen/sized pgen/model)])]
                (assoc-ok m path k i)))
