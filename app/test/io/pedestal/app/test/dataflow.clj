(ns io.pedestal.app.test.dataflow
  (:require [io.pedestal.app.messages :as msg])
  (:use io.pedestal.app.dataflow
        clojure.test))

(deftest test-matching-path?
  (is (matching-path? [] []))
  (is (matching-path? [:a] [:a]))
  (is (matching-path? [:a :b] [:a :b]))
  (is (matching-path? [:*] [:*]))
  (is (matching-path? [:*] [:a]))
  (is (not (matching-path? [:a] [])))
  (is (not (matching-path? [:a] [:b])))
  (is (not (matching-path? [:a :b] [:a :c])))
  (is (not (matching-path? [:* :b] [:* :c]))))

(deftest test-descendent?
  (is (descendent? [] []))
  (is (descendent? [:a] []))
  (is (descendent? [:a] [:a]))
  (is (descendent? [:a :b] [:a]))
  (is (descendent? [:a :b :c] [:a]))
  (is (descendent? [:a :b :c] [:a :b]))
  (is (descendent? [:a :* :c] [:a :b]))
  (is (not (descendent? [:a] [:b])))
  (is (not (descendent? [:a :b] [:a :c])))
  (is (not (descendent? [:a :b :c] [:a :b :g])))
  (is (not (descendent? [:a :* :c] [:a :b :g]))))

(deftest test-topo-sort
  (let [topo-visit #'io.pedestal.app.dataflow/topo-visit
        graph {1 {:deps #{}}
               2 {:deps #{1}}
               3 {:deps #{2}}
               4 {:deps #{1 2}}
               5 {:deps #{3 6}}
               6 {:deps #{4 5}}}]
    (is (= (:io.pedestal.app.dataflow/order
            (reduce topo-visit (assoc graph :io.pedestal.app.dataflow/order []) (keys graph)))
           [1 2 3 4 6 5]))))

(defn valid-sort? [seq]
  (every? #(not (some (partial descendent? (:output %)) (:inputs %)))
          (:return (reduce (fn [a [f ins out]]
                             {:inputs (concat (:inputs a) ins)
                              :return (conj (:return a) {:output out :inputs (:inputs a)})})
                           {:inputs []
                            :return []}
                           seq))))

(deftest test-sort-derive-fns
  (is (= (sort-derive-fns [['b #{[:a]} [:b]]
                           ['c #{[:b]} [:c]]])
         [['b #{[:a]} [:b]]
          ['c #{[:b]} [:c]]]))
  (is (valid-sort? (sort-derive-fns [['b #{[:a]} [:b]]
                                     ['c #{[:b]} [:c]]])))
  (is (= (sort-derive-fns [['c #{[:b]} [:c]]
                           ['b #{[:a]} [:b]]])
         [['b #{[:a]} [:b]]
          ['c #{[:b]} [:c]]]))
  (is (valid-sort? (sort-derive-fns [['c #{[:b]} [:c]]
                                     ['b #{[:a]} [:b]]])))
  (is (valid-sort? (sort-derive-fns [['k #{[:d :*]}    [:k]]
                                     ['c #{[:b]}       [:c]]
                                     ['d #{[:b :c]}    [:d :e]]
                                     ['g #{[:d :e :f]} [:g :h :i]]
                                     ['b #{[:a]}       [:b]]])))
  (is (valid-sort? (sort-derive-fns [['d #{[:c]} [:d]]
                                     ['e #{[:d]} [:e]]
                                     ['b #{[:a]} [:b]]
                                     ['a #{[:x]} [:a]]
                                     ['c #{[:b]} [:c]]])))
  (is (= (sort-derive-fns [['e #{[:c] [:d]} [:e]]
                           ['d #{[:b]}      [:d]]
                           ['b #{[:a]}      [:b]]
                           ['c #{[:b]}      [:c]]])
         [['b #{[:a]}      [:b]]
          ['d #{[:b]}      [:d]]
          ['c #{[:b]}      [:c]]
          ['e #{[:c] [:d]} [:e]]]))
  (is (valid-sort? (sort-derive-fns [['d #{[:b]}      [:d]]
                                     ['e #{[:c] [:d]} [:e]]
                                     ['c #{[:b]}      [:c]]
                                     ['b #{[:a]}      [:b]]]))))

(deftest test-sorted-derive-vector
  (is (= (sorted-derive-vector [{:in #{[:b]} :out [:c] :fn 'c}
                                {:in #{[:a]} :out [:b] :fn 'b}])
         [['b #{[:a]} [:b]]
          ['c #{[:b]} [:c]]])))

(deftest test-build
  (is (= (build {:derive [{:in #{[:a]} :out [:b] :fn 'b}
                          {:in #{[:b]} :out [:c] :fn 'c}]})
         {:derive [['b #{[:a]} [:b]]
                   ['c #{[:b]} [:c]]]})))

(deftest test-find-transform
  (let [config [[:a [:b] 'x]
                [:c [:d] 'y]
                [:g [:d :e :*] 'z]
                [:g [:d :e :**] 'j]
                [:* [:d :* :f] 'i]]]
    (is (= (find-transform config [:d] :c)
           'y))
    (is (= (find-transform config [:d :e :f] :g)
           'z))
    (is (= (find-transform config [:d :e :f :g] :g)
           'j))
    (is (= (find-transform config [:d :e :f] :z)
           'i))))

(deftest test-transform-phase
  (let [inc-fn (fn [o _] (inc o))]
    (is (= (transform-phase {:old {:data-model {:a 0}}
                             :new {:data-model {:a 0}}
                             :dataflow {:transform [[:inc [:a] inc-fn]]}
                             :context {:message {::msg/topic [:a] ::msg/type :inc}}})
           {:change {:updated #{[:a]}}
            :old {:data-model {:a 0}}
            :new {:data-model {:a 1}}
            :dataflow {:transform [[:inc [:a] inc-fn]]}
            :context {:message {::msg/topic [:a] ::msg/type :inc}}}))))

(deftest test-filter-inputs
  (is (= (filter-inputs #{[:a]} #{[:a]})
         #{[:a]}))
  (is (= (filter-inputs #{[:a]} #{[:a] [:b]})
         #{[:a]}))
  (let [changes #{[:a :b 1] [:g 1 :h 2] [:m :n 3 :x]}]
    (is (= (filter-inputs #{[:a :* :*]} changes)
           #{[:a :b 1]}))
    (is (= (filter-inputs #{[:a :b :*]} changes)
           #{[:a :b 1]}))
    (is (= (filter-inputs #{[:a :b :*] [:m :n :* :*]} changes)
           #{[:a :b 1] [:m :n 3 :x]}))
    (is (= (filter-inputs #{[:a :b :*] [:g :* :h :*]} changes)
           #{[:a :b 1] [:g 1 :h 2]}))))

(deftest test-inputs-changed?
  (is (inputs-changed? {:updated #{[:a]}} #{[:a]}))
  (is (inputs-changed? {:updated #{[:a]} :added #{[:b]}} #{[:a]}))
  (is (inputs-changed? {:updated #{[:a]} :added #{[:b]}} #{[:a]}))
  (is (inputs-changed? {:updated #{[:a :b :c]}} #{[:a :*]}))
  (is (not (inputs-changed? {:updated #{[:a :b :c]}} #{[:b :*]}))))

(deftest test-derive-phase
  (let [double-sum-fn (fn [_ input] (* 2 (reduce + (input-vals input))))]
    (is (= (derive-phase {:change {:updated #{[:a]}}
                          :old {:data-model {:a 0}}
                          :new {:data-model {:a 2}}
                          :dataflow {:derive [[double-sum-fn #{[:a]} [:b]]]}
                          :context {}})
           {:change {:added #{[:b]} :updated #{[:a]}}
            :old {:data-model {:a 0}}
            :new {:data-model {:a 2 :b 4}}
            :dataflow {:derive [[double-sum-fn #{[:a]} [:b]]]}
            :context {}}))
    (is (= (derive-phase {:change {:updated #{[:a]}}
                          :old {:data-model {:a 0}}
                          :new {:data-model {:a 2}}
                          :dataflow {:derive [[double-sum-fn #{[:a]} [:b]]
                                              [double-sum-fn #{[:a]} [:c]]]}
                          :context {}})
           {:change {:added #{[:b] [:c]} :updated #{[:a]}}
            :old {:data-model {:a 0}}
            :new {:data-model {:a 2 :b 4 :c 4}}
            :dataflow {:derive [[double-sum-fn #{[:a]} [:b]]
                                [double-sum-fn #{[:a]} [:c]]]}
            :context {}}))))

(defn inc-transform [old-value message]
  (inc old-value))

(defn double-derive [_ input]
  (* 2 (reduce + (input-vals input))))

(defn simple-emit [old-input new-input context])

(deftest test-flow-step
  (let [dataflow (build {:transform [[:inc [:a] inc-transform]]
                         :derive [{:in #{[:a]} :out [:b] :fn double-derive}]
                         :emit [#{[]} simple-emit]})]
    (is (= (flow-step dataflow {:data-model {:a 0}} {::msg/topic [:a] ::msg/type :inc})
           {:data-model {:a 1 :b 2}}))))

;; Ported tests
;; ================================================================================

(defn sum [_ input]
  (reduce + (input-vals input)))

(deftest test-topo-sort-again
  (let [topo-visit #'io.pedestal.app.dataflow/topo-visit
        graph {1 {:deps #{}}
               2 {:deps #{1}}
               3 {:deps #{2}}
               4 {:deps #{1 2}}
               5 {:deps #{3 6}}
               6 {:deps #{4 5}}}]
    (is (= (:io.pedestal.app.dataflow/order
            (reduce topo-visit (assoc graph :io.pedestal.app.dataflow/order []) (keys graph)))
           [1 2 3 4 6 5]))))

(def dataflow-test-one
  {:transform [[:inc [:x] inc-transform]]
   :derive    [{:fn sum :in #{[:x]}      :out [:a]}
               {:fn sum :in #{[:x] [:a]} :out [:b]}
               {:fn sum :in #{[:b]}      :out [:c]}
               {:fn sum :in #{[:a]}      :out [:d]}
               {:fn sum :in #{[:c] [:d]} :out [:e]}]})

(deftest test-dataflow-one
  (let [dataflow (build dataflow-test-one)]
    (is (= (flow-step dataflow {:data-model {:x 0}} {::msg/topic [:x] ::msg/type :inc})
           {:data-model {:x 1 :a 1 :b 2 :d 1 :c 2 :e 3}}))))
