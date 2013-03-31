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

(deftest test-build
  (is (= (build {:derive {'b {:in #{[:a]} :out [:b]}
                          'c {:in #{[:b]} :out [:c]}}})
         {:derive [['b #{[:a]} [:b]]
                   ['c #{[:b]} [:c]]]})))

(defn inc-transform [old-value message]
  (inc old-value))

(defn double-derive [_ inputs _]
  (* 2 (apply + (map :new (vals inputs)))))

(defn simple-emit [inputs context])

(deftest test-flow-step
  (let [dataflow (build {:transform [[:inc [:a] inc-transform]]
                         :derive {double-derive {:in #{[:a]} :out [:b]}}
                         :emit [#{[]} simple-emit]})]
    (is (= (flow-step dataflow {:data-model {:a 0}} {::msg/topic [:a] ::msg/type :inc})
           {:data-model {:a 1 :b 2}}))))
