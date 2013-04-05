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
          ['c #{[:b]}      [:c]]
          ['d #{[:b]}      [:d]]
          ['e #{[:c] [:d]} [:e]]]))
  (is (valid-sort? (sort-derive-fns [['d #{[:b]}      [:d]]
                                     ['e #{[:c] [:d]} [:e]]
                                     ['c #{[:b]}      [:c]]
                                     ['b #{[:a]}      [:b]]]))))

(deftest test-sorted-derive-vector
  (is (= (sorted-derive-vector {{:in #{[:b]} :out [:c]} 'c
                                {:in #{[:a]} :out [:b]} 'b})
         [['b #{[:a]} [:b]]
          ['c #{[:b]} [:c]]])))

(deftest test-build
  (is (= (build {:derive {{:in #{[:a]} :out [:b]} 'b
                          {:in #{[:b]} :out [:c]} 'c}})
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
           {:old {:data-model {:a 0}}
            :new {:data-model {:a 1}}
            :dataflow {:transform [[:inc [:a] inc-fn]]}
            :context {:message {::msg/topic [:a] ::msg/type :inc}}}))))

(deftest test-partition-wildcard-path
  (is (= (partition-wildcard-path [:a])
         [[:a]]))
  (is (= (partition-wildcard-path [:a :b])
         [[:a :b]]))
  (is (= (partition-wildcard-path [:a :*])
         [[:a] [:*]]))
  (is (= (partition-wildcard-path [:a :b :* :c])
         [[:a :b] [:*] [:c]]))
  (is (= (partition-wildcard-path [:a :* :* :c])
         [[:a] [:*] [:*] [:c]])))

(deftest test-components-for-path
  (let [d {:a {1 {:x 101 :y 201 :z {1 :a 2 :b 3 :c}}
               2 {:x 102 :y 202 :z {1 :d 2 :e 3 :f}}
               3 {:x 103 :y 203 :z {1 :g 2 :h 3 :i}}
               4 {:x 104 :y 204 :z {1 :j 2 :k 3 :l}}
               5 {:x 105 :y 205 :z {1 :m 2 :n 3 :0}}}
           :b {:x 999 :y 888 :z {1 :m 2 :n 3 :o}}}]
    (is (= (components-for-path d [:b])
           {[:b] {:x 999 :y 888 :z {1 :m 2 :n 3 :o}}}))
    (is (= (components-for-path d [:b :*])
           {[:b :x] 999
            [:b :y] 888
            [:b :z] {1 :m 2 :n 3 :o}}))
    (is (= (components-for-path d [:b :z :*])
           {[:b :z 1] :m
            [:b :z 2] :n
            [:b :z 3] :o}))
    (is (= (components-for-path d [:a :* :z 1])
           {[:a 1 :z 1] :a
            [:a 2 :z 1] :d
            [:a 3 :z 1] :g
            [:a 4 :z 1] :j
            [:a 5 :z 1] :m}))))

(deftest test-changes
  (is (= (changes {:inputs #{[:a]}})
         {:added #{}
          :removed #{}
          :updated #{}})))

(deftest test-derive-phase
  (let [double-sum-fn (fn [old-model old-input new-input context]
                        (* 2 (reduce + (map #(get-in new-input %) (:inputs context)))))]
    (is (= (derive-phase {:old {:data-model {:a 0}}
                          :new {:data-model {:a 2}}
                          :dataflow {:derive [[double-sum-fn #{[:a]} [:b]]]}
                          :context {}})
           {:old {:data-model {:a 0}}
            :new {:data-model {:a 2 :b 4}}
            :dataflow {:derive [[double-sum-fn #{[:a]} [:b]]]}
            :context {}}))
    (is (= (derive-phase {:old {:data-model {:a 0}}
                          :new {:data-model {:a 2}}
                          :dataflow {:derive [[double-sum-fn #{[:a]} [:b]]
                                              [double-sum-fn #{[:a]} [:c]]]}
                          :context {}})
           {:old {:data-model {:a 0}}
            :new {:data-model {:a 2 :b 4 :c 4}}
            :dataflow {:derive [[double-sum-fn #{[:a]} [:b]]
                                [double-sum-fn #{[:a]} [:c]]]}
            :context {}}))))

(defn inc-transform [old-value message]
  (inc old-value))

(defn double-derive [_ _ new-input context]
  (* 2 (reduce + (map #(get-in new-input %) (:inputs context)))))

(defn simple-emit [old-input new-input context])

(deftest test-flow-step
  (let [dataflow (build {:transform [[:inc [:a] inc-transform]]
                         :derive {{:in #{[:a]} :out [:b]} double-derive}
                         :emit [#{[]} simple-emit]})]
    (is (= (flow-step dataflow {:data-model {:a 0}} {::msg/topic [:a] ::msg/type :inc})
           {:data-model {:a 1 :b 2}}))))
