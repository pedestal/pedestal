(ns io.pedestal.app.test.dataflow
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
