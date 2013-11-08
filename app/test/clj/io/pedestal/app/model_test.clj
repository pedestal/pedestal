; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

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
    (is (= (set (:inform (apply-transform {} [[[:a] assoc :x 1]])))
           (ideal-change-report {} {:a {:x 1}}))))
  (testing "message with no args"
    (is (= (set (:inform (apply-transform {:a 1} [[[:a] inc]])))
           (ideal-change-report {:a 1} {:a 2}))))
  (testing "message with one arg"
    (is (= (set (:inform (apply-transform {:a 1} [[[:a] + 2]])))
           (ideal-change-report {:a 1} {:a 3}))))
  (testing "multiple messages"
    (is (= (set (:inform (apply-transform {:a {:b 1} :c 2} [[[:c] inc] [[:a] dissoc :b]])))
           (ideal-change-report {:a {:b 1} :c 2} {:a {} :c 3}))))
  (testing "multiple messages on the same path"
    (is (= (set (:inform (apply-transform {:a {:b 1} :c 2} [[[:a] assoc :d 3] [[:a] dissoc :b]])))
           (ideal-change-report {:a {:b 1} :c 2} {:a {:d 3} :c 2}))))
  (testing "updates internal keys"
    (let [bump (fn [values x] (reduce (fn [a [k v]]
                                       (if (< v x)
                                         (assoc a k (inc v))
                                         (assoc a k v)))
                                     {}
                                     values))]
      (is (= (set (:inform (apply-transform {:a {:b 1 :c 5 :d 9 :e 8} :f 2} [[[:a] bump 6]])))
             (ideal-change-report {:a {:c 5, :b 1, :d 9, :e 8}, :f 2}
                                  {:a {:e 8, :d 9, :b 2, :c 6}, :f 2})))))
  (testing "removes internal keys"
    (let [clean-up (fn [values] (reduce (fn [a [k v]]
                                         (if (> v 5)
                                           a
                                           (assoc a k v)))
                                       {}
                                       values))]
      (is (= (set (:inform (apply-transform {:a {:b 1 :d 5 :e 9 :f 8} :c 2} [[[:a] clean-up]])))
             (ideal-change-report {:a {:b 1, :f 8, :d 5, :e 9}, :c 2} {:a {:d 5, :b 1}, :c 2})))))
  (testing "different transforms produce same results"
    (is (= (set (:inform (apply-transform {:a {:b 0 :c 0 :d 0}} [[[:a] (constantly {:b 1 :c 1 :d 1})]])))
           (set (:inform (apply-transform {:a {:b 0 :c 0 :d 0}} [[[:a] assoc :b 1]
                                                                   [[:a] assoc :c 1]
                                                                   [[:a] assoc :d 1]])))
           (set (:inform (apply-transform {:a {:b 0 :c 0 :d 0}} [[[:a :b] inc]
                                                                   [[:a :c] inc]
                                                                   [[:a :d] inc]])))
           (ideal-change-report {:a {:b 0 :c 0 :d 0}} {:a {:b 1 :c 1 :d 1}}))))
  (testing "replace a sub-map"
    (is (= (set (:inform (apply-transform {:a {:b {:c 0 :d 0}}}
                                            [[[:a] (constantly {:b {:c 0 :d 1}})]])))
           (ideal-change-report {:a {:b {:c 0 :d 0}}} {:a {:b {:c 0 :d 1}}})))))

;; Consider discouraging this by adding logging or even throwing an exception
(deftest transform-from-value-to-map-returns-larger-diffs
  (testing "value to map change at [:a :b]"
    (is (= (set (:inform (apply-transform {:a {:b 1}} [[[:a :b] (constantly {:c {:d 2}})]])))
           #{[[:a :b :c :d] :added {:a {:b 1}} {:a {:b {:c {:d 2}}}}]})))
  (testing "map to value change at [:a :b]"
    (is (= (set (:inform (apply-transform {:a {:b {:c {:d 2}}}} [[[:a :b] (constantly 1)]])))
           #{[[:a :b :c :d] :removed {:a {:b {:c {:d 2}}}} {:a {:b 1}}]
             [[:a :b] :updated {:a {:b {:c {:d 2}}}} {:a {:b 1}}]}))))

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
      (= (set (:inform (apply-transform m [[path assoc k v]])))
         (ideal-change-report m (update-in m path assoc k v)))))

(defspec assoc-model-tests
  50
  (prop/for-all [m (gen/such-that not-empty (gen/sized pgen/model))
                 k gen/keyword
                 i (gen/one-of [gen/int (gen/sized pgen/model)])]
                (assoc-ok m [(ffirst m)] k i)))

(defn valid-inform-for [transform-fn m path & args]
  (= (set (:inform (apply-transform m [(into [path transform-fn] args)])))
     (ideal-change-report m (apply update-in m path transform-fn args))))

(defspec inc-tests
  50
  (prop/for-all [m (gen/such-that not-empty (gen/sized pgen/model))
                 i gen/int]
                (let [path [(rand-nth (keys m))]
                      model (assoc-in m path i)]
                  (valid-inform-for inc model path))))

(defspec dissoc-model-tests
  50
  (prop/for-all [m (gen/such-that not-empty (gen/sized pgen/model-with-map-values))
                 k gen/keyword
                 v gen/nat]
                (let [path [(rand-nth (keys m))]
                      ;; ensure we're always dissocing something at a path
                      ;; otherwise we're testing no change report
                      model (assoc-in m (conj path k) v)]
                  (valid-inform-for dissoc model path k))))

(defspec update-in-model-tests
  50
  (prop/for-all [m (gen/such-that not-empty (gen/sized pgen/model-with-map-values))
                 k gen/keyword
                 v gen/nat]
                (let [path [(rand-nth (keys m))]
                      ;; ensure we can update something at a path
                      ;; otherwise we're testing no change report
                      model (assoc-in m (conj path k) v)]
                  (valid-inform-for update-in model path [k] inc))))

(defspec merge-model-tests
  50
  (prop/for-all [m (gen/such-that not-empty (gen/sized pgen/model-with-map-values))
                 k gen/keyword
                 old-map (gen/map gen/keyword gen/nat)
                 new-map (gen/such-that not-empty (gen/map gen/keyword gen/nat))]
                (let [path [(rand-nth (keys m))]
                      ;; ensure we can merge something at a path
                      ;; otherwise we're testing no change report
                      model (assoc-in m (conj path k) old-map)]
                  (valid-inform-for merge model path new-map))))
