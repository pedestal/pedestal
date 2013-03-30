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
          (:return (reduce (fn [a [out f ins]]
                             {:inputs (concat (:inputs a) ins)
                              :return (conj (:return a) {:output out :inputs (:inputs a)})})
                           {:inputs []
                            :return []}
                           seq))))

(deftest test-sort-derive-fns
  (is (= (sort-derive-fns [[[:b] 'b #{[:a]}]
                           [[:c] 'c #{[:b]}]])
         [[[:b] 'b #{[:a]}]
          [[:c] 'c #{[:b]}]]))
  (is (valid-sort? (sort-derive-fns [[[:b] 'b #{[:a]}]
                                     [[:c] 'c #{[:b]}]])))
  (is (= (sort-derive-fns [[[:c] 'c #{[:b]}]
                           [[:b] 'b #{[:a]}]])
         [[[:b] 'b #{[:a]}]
          [[:c] 'c #{[:b]}]]))
  (is (valid-sort? (sort-derive-fns [[[:c] 'c #{[:b]}]
                                     [[:b] 'b #{[:a]}]])))
  (is (valid-sort? (sort-derive-fns [[[:k]       'k #{[:d :*]}]
                                     [[:c]       'c #{[:b]}]
                                     [[:d :e]    'd #{[:b :c]}]
                                     [[:g :h :i] 'g #{[:d :e :f]}]
                                     [[:b]       'b #{[:a]}]])))
  (is (valid-sort? (sort-derive-fns [[[:d] 'd #{[:c]}]
                                     [[:e] 'e #{[:d]}]
                                     [[:b] 'b #{[:a]}]
                                     [[:a] 'a #{[:x]}]
                                     [[:c] 'c #{[:b]}]])))
  (is (= (sort-derive-fns [[[:e] 'e #{[:c] [:d]}]
                           [[:d] 'd #{[:b]}]
                           [[:b] 'b #{[:a]}]
                           [[:c] 'c #{[:b]}]])
         [[[:b] 'b #{[:a]}]
          [[:c] 'c #{[:b]}]
          [[:d] 'd #{[:b]}]
          [[:e] 'e #{[:c] [:d]}]]))
  (is (valid-sort? (sort-derive-fns [[[:d] 'd #{[:b]}]
                                     [[:e] 'e #{[:c] [:d]}]
                                     [[:c] 'c #{[:b]}]
                                     [[:b] 'b #{[:a]}]]))))
