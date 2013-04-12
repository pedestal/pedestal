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
            :context {}}))
    (testing "returned maps record change"
      (let [d (fn [_ input] {:x {:y 11}})]
        (is (= (derive-phase {:change {:updated #{[:a]}}
                              :old {:data-model {:a 0 :b {:c {:x {:y 10 :z 15}}}}}
                              :new {:data-model {:a 2 :b {:c {:x {:y 10 :z 15}}}}}
                              :dataflow {:derive [[d #{[:a]} [:b :c]]]}
                              :context {}})
               {:change {:updated #{[:a] [:b] [:b :c]} :inspect #{[:b :c]}}
                :old {:data-model {:a 0 :b {:c {:x {:y 10 :z 15}}}}}
                :new {:data-model {:a 2 :b {:c {:x {:y 11}}}}}
                :dataflow {:derive [[d #{[:a]} [:b :c]]]}
                :context {}}))))))

(deftest test-continue-phase
  (let [continue-fn (fn [input] [{::msg/topic :x ::msg/type :y :value (single-val input)}])]
    (is (= (continue-phase {:change {:updated #{[:a]}}
                          :old {:data-model {:a 0}}
                          :new {:data-model {:a 2}}
                          :dataflow {:continue #{{:fn continue-fn :in #{[:a]}}}}
                          :context {}})
           {:change {:updated #{[:a]}}
            :old {:data-model {:a 0}}
            :new {:data-model {:a 2}
                  :continue [{::msg/topic :x ::msg/type :y :value 2}]}
            :dataflow {:continue #{{:fn continue-fn :in #{[:a]}}}}
            :context {}}))))

(deftest test-effect-phase
  (let [output-fn (fn [input] [{::msg/topic :x ::msg/type :y :value (single-val input)}])]
    (is (= (effect-phase {:change {:updated #{[:a]}}
                          :old {:data-model {:a 0}}
                          :new {:data-model {:a 2}}
                          :dataflow {:effect #{{:fn output-fn :in #{[:a]}}}}
                          :context {}})
           {:change {:updated #{[:a]}}
            :old {:data-model {:a 0}}
            :new {:data-model {:a 2}
                  :effect [{::msg/topic :x ::msg/type :y :value 2}]}
            :dataflow {:effect #{{:fn output-fn :in #{[:a]}}}}
            :context {}}))))



;; Complete dataflow tests
;; ================================================================================

(defn inc-transform [old-value message]
  (inc old-value))

(defn double-derive [_ input]
  (* 2 (reduce + (input-vals input))))

(defn continue-min [n]
  (fn [input]
    (when (< (single-val input) n)
      [{::msg/topic [:a] ::msg/type :inc}])))

(def flows
  {:one-derive     {:transform [[:inc [:a] inc-transform]]
                    :derive #{{:in #{[:a]} :out [:b] :fn double-derive}}}
   
   :continue-to-10 {:transform [[:inc [:a] inc-transform]]
                    :derive #{{:fn double-derive :in #{[:a]} :out [:b]}}
                    :continue #{{:fn (continue-min 10) :in #{[:b]}}}}})

(defn flow [k]
  (build (k flows)))

(deftest test-flow-phases-step
  (is (= (flow-phases-step (flow :one-derive) {:data-model {:a 0}} {::msg/topic [:a] ::msg/type :inc})
         {:data-model {:a 1 :b 2}}))
  (is (= (flow-phases-step (flow :continue-to-10) {:data-model {:a 0}} {::msg/topic [:a] ::msg/type :inc})
         {:data-model {:a 1 :b 2}
          :continue [{::msg/topic [:a] ::msg/type :inc}]})))

(deftest test-run-flow-phases
  (is (= (run-flow-phases (flow :one-derive) {:data-model {:a 0}} {::msg/topic [:a] ::msg/type :inc})
         {:data-model {:a 1 :b 2}}))
  (is (= (run-flow-phases (flow :continue-to-10) {:data-model {:a 0}} {::msg/topic [:a] ::msg/type :inc})
         {:data-model {:a 5 :b 10}})))

(deftest test-run
  (is (= (run (flow :one-derive) {:a 0} {::msg/topic [:a] ::msg/type :inc})
         {:data-model {:a 1 :b 2}}))
  (is (= (run (flow :continue-to-10) {:a 0} {::msg/topic [:a] ::msg/type :inc})
         {:data-model {:a 5 :b 10}})))


(deftest test-get-path
  (is (= (get-path {:x {0 {:y {0 {:a 1
                                  :b 5}}}
                        1 {:y {0 {:a 1}
                               1 {:b 2}}}}
                    :sum {:b 7
                          :a 2}}
                   [:x :*])
         [[[:x 0] {:y {0 {:a 1 :b 5}}}]
          [[:x 1] {:y {0 {:a 1} 1 {:b 2}}}]])))

(deftest test-input-map
  (is (= (input-map {:new-model {:a 3 :b 5}
                     :input-paths #{[:a] [:b]}})
         {[:a] 3 [:b] 5}))
  (is (= (input-map {:new-model {:a {0 {:g 1}
                                     1 {:g 2}}
                                 :b 5}
                     :input-paths #{[:a :* :g] [:b]}})
         {[:a 0 :g] 1
          [:a 1 :g] 2
          [:b]      5})))

(deftest test-input-vals
  (is (= (set (input-vals {:new-model {:a 3 :b 5}
                           :input-paths #{[:a] [:b]}}))
         #{3 5}))
  (is (= (set (input-vals {:new-model {:a {0 {:g 1}
                                           1 {:g 2}}
                                       :b 5}
                           :input-paths #{[:a :* :g] [:b]}}))
         #{1 2 5})))


;; Ported tests
;; ================================================================================

(defn sum [_ input]
  (reduce + (input-vals input)))

(deftest test-dataflows
  (let [dataflow (build {:transform [[:inc [:x] inc-transform]]
                         :derive    #{{:fn sum :in #{[:x]}      :out [:a]}
                                      {:fn sum :in #{[:x] [:a]} :out [:b]}
                                      {:fn sum :in #{[:b]}      :out [:c]}
                                      {:fn sum :in #{[:a]}      :out [:d]}
                                      {:fn sum :in #{[:c] [:d]} :out [:e]}}})]
    (is (= (run dataflow {:x 0} {::msg/topic [:x] ::msg/type :inc})
           {:data-model {:x 1 :a 1 :b 2 :d 1 :c 2 :e 3}})))
  
  (let [dataflow (build {:transform [[:inc [:x] inc-transform]]
                         :derive    #{{:fn sum :in #{[:x]}           :out [:a]}
                                      {:fn sum :in #{[:a]}           :out [:b]}
                                      {:fn sum :in #{[:a]}           :out [:c]}
                                      {:fn sum :in #{[:c]}           :out [:d]}
                                      {:fn sum :in #{[:c]}           :out [:e]}
                                      {:fn sum :in #{[:d] [:e]}      :out [:f]}
                                      {:fn sum :in #{[:a] [:b] [:f]} :out [:g]}
                                      {:fn sum :in #{[:g]}           :out [:h]}
                                      {:fn sum :in #{[:g] [:f]}      :out [:i]}
                                      {:fn sum :in #{[:i] [:f]}      :out [:j]}
                                      {:fn sum :in #{[:h] [:g] [:j]} :out [:k]}}})]
    (is (= (run dataflow {:x 0} {::msg/topic [:x] ::msg/type :inc})
           {:data-model {:x 1 :a 1 :b 1 :c 1 :d 1 :e 1 :f 2 :g 4 :h 4 :i 6 :j 8 :k 16}})))
  
  (let [dataflow (build {:transform [[:inc [:x :* :y :* :b] inc-transform]]
                         :derive    [{:fn sum :in #{[:x :* :y :* :b]} :out [:sum :b]}]})]
    (is (= (run dataflow {:x {0 {:y {0 {:a 1
                                        :b 5}}}
                              1 {:y {0 {:a 1}
                                     1 {:b 2}}}}
                          :sum {:b 7
                                :a 2}}
                      {::msg/topic [:x 1 :y 1 :b] ::msg/type :inc})
           {:data-model {:x {0 {:y {0 {:a 1
                                       :b 5}}}
                             1 {:y {0 {:a 1}
                                    1 {:b 3}}}}
                         :sum {:a 2
                               :b 8}}}))))
