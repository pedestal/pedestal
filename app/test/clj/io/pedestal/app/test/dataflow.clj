; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.test.dataflow
  (:require [io.pedestal.app.messages :as msg])
  (:use io.pedestal.app.dataflow
        io.pedestal.app.util.test
        clojure.test))

(refer-privates io.pedestal.app.dataflow :all)

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
  (let [graph {1 {:deps #{}}
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
          (:return (reduce (fn [a {f :fn ins :in out :out}]
                             {:inputs (concat (:inputs a) ins)
                              :return (conj (:return a) {:output out :inputs (:inputs a)})})
                           {:inputs []
                            :return []}
                           seq))))

(deftest test-sort-derive-fns
  (is (= (sort-derive-fns [{:fn 'b :in #{[:a]} :out [:b]}
                           {:fn 'c :in #{[:b]} :out [:c]}])
         [{:fn 'b :in #{[:a]} :out [:b]}
          {:fn 'c :in #{[:b]} :out [:c]}]))
  (is (valid-sort? (sort-derive-fns [{:fn 'b :in #{[:a]} :out [:b]}
                                     {:fn 'c :in #{[:b]} :out [:c]}])))
  (is (= (sort-derive-fns [{:fn 'c :in #{[:b]} :out [:c]}
                           {:fn 'b :in #{[:a]} :out [:b]}])
         [{:fn 'b :in #{[:a]} :out [:b]}
          {:fn 'c :in #{[:b]} :out [:c]}]))
  (is (valid-sort? (sort-derive-fns [{:fn 'c :in #{[:b]} :out [:c]}
                                     {:fn 'b :in #{[:a]} :out [:b]}])))
  (is (valid-sort? (sort-derive-fns [{:fn 'k :in #{[:d :*]}    :out [:k]}
                                     {:fn 'c :in #{[:b]}       :out [:c]}
                                     {:fn 'd :in #{[:b :c]}    :out [:d]}
                                     {:fn 'g :in #{[:d :e :f]} :out [:g :h]}
                                     {:fn 'b :in #{[:a]}       :out [:b]}])))
  (is (valid-sort? (sort-derive-fns [{:fn 'd :in #{[:c]} :out [:d]}
                                     {:fn 'e :in #{[:d]} :out [:e]}
                                     {:fn 'b :in #{[:a]} :out [:b]}
                                     {:fn 'a :in #{[:x]} :out [:a]}
                                     {:fn 'c :in #{[:b]} :out [:c]}])))
  (is (= (sort-derive-fns [{:fn 'e :in #{[:c] [:d]} :out [:e]}
                           {:fn 'd :in #{[:b]}      :out [:d]}
                           {:fn 'b :in #{[:a]}      :out [:b]}
                           {:fn 'c :in #{[:b]}      :out [:c]}])
         [{:fn 'b :in #{[:a]} :out      [:b]}
          {:fn 'd :in #{[:b]} :out      [:d]}
          {:fn 'c :in #{[:b]} :out      [:c]}
          {:fn 'e :in #{[:c] [:d]} :out [:e]}]))
  (is (valid-sort? (sort-derive-fns [{:fn 'd :in #{[:b]}      :out [:d]}
                                     {:fn 'e :in #{[:c] [:d]} :out [:e]}
                                     {:fn 'c :in #{[:b]}      :out [:c]}
                                     {:fn 'b :in #{[:a]}      :out [:b]}]))))

(deftest test-build
  (is (= (build {:derive [{:in #{[:a]} :out [:b] :fn 'b}
                          {:in #{[:b]} :out [:c] :fn 'c}]})
         {:input-adapter identity
          :derive [{:fn 'b :in #{[:a]} :out [:b] :arg-names nil}
                   {:fn 'c :in #{[:b]} :out [:c] :arg-names nil}]
          :transform []
          :continue #{}
          :effect #{}
          :emit []}))
  (is (= (build {:transform [[:inc [:a] 'x]
                             {:key :dec :out [:b] :fn 'y}]})
         {:input-adapter identity
          :transform [{:key :inc :out [:a] :fn 'x}
                      {:key :dec :out [:b] :fn 'y}]
          :derive ()
          :continue #{}
          :effect #{}
          :emit []}))
  (is (= (build {:transform [[:inc [:a] 'x]
                             {:key :dec :out [:b] :fn 'y}]
                 :derive [{:in #{[:a]} :out [:b] :fn 'b}
                          [#{[:b]} [:c] 'c]]})
         {:input-adapter identity
          :transform [{:key :inc :out [:a] :fn 'x}
                      {:key :dec :out [:b] :fn 'y}]
          :derive [{:fn 'b :in #{[:a]} :out [:b] :arg-names nil}
                   {:fn 'c :in #{[:b]} :out [:c] :args nil :arg-names nil}]
          :continue #{}
          :effect #{}
          :emit []}))
  (is (= (build {:transform [[:inc [:a] 'a]
                             {:key :dec :out [:b] :fn 'b}]
                 :derive [{:in #{[:a]} :out [:b] :fn 'c}
                          [#{[:b]} [:c] 'd]]
                 :continue #{{:in #{[:x]} :fn 'e}
                             [#{[:y]} 'f]}
                 :effect #{{:in #{[:w]} :fn 'g}
                           [#{[:p]} 'h]}
                 :emit [{:in #{[:q]} :fn 'i}
                        [#{[:s]} 'j]]})
         {:input-adapter identity
          :transform [{:key :inc :out [:a] :fn 'a}
                      {:key :dec :out [:b] :fn 'b}]
          :derive [{:fn 'c :in #{[:a]} :out [:b] :arg-names nil}
                   {:fn 'd :in #{[:b]} :out [:c] :args nil :arg-names nil}]
          :continue #{{:in #{[:x]} :fn 'e :arg-names nil}
                      {:in #{[:y]} :fn 'f :args nil :arg-names nil}}
          :effect #{{:in #{[:w]} :fn 'g :arg-names nil}
                    {:in #{[:p]} :fn 'h :args nil :arg-names nil}}
          :emit [{:in #{[:q]} :fn 'i :arg-names nil}
                 {:in #{[:s]} :fn 'j :args nil :arg-names nil}]})))

(deftest test-find-message-transformer
  (let [config [{:key :a :out [:b] :fn 'x}
                {:key :c :out [:d] :fn 'y}
                {:key :g :out [:d :e :*] :fn 'z}
                {:key :g :out [:d :e :**] :fn 'j}
                {:key :* :out [:d :* :f] :fn 'i}]]
    (is (= (find-message-transformer config [:d] :c)
           'y))
    (is (= (find-message-transformer config [:d :e :f] :g)
           'z))
    (is (= (find-message-transformer config [:d :e :f :g] :g)
           'j))
    (is (= (find-message-transformer config [:d :e :f] :z)
           'i))))

(deftest test-transform-phase
  (let [inc-fn (fn [o _] (inc o))
        state {:old {:data-model {:a 0}}
               :new {:data-model {:a 0}}
               :dataflow {:input-adapter identity
                          :transform [{:key :inc :out [:a] :fn inc-fn}]}
               :context {:message {:out [:a] :key :inc}}}]
    (is (= (transform-phase state)
           (-> state
               (assoc-in [:change :updated] #{[:a]})
               (assoc-in [:new :data-model :a] 1))))))

(deftest test-filter-inputs
  (is (= (input-set #{[:a]} filter #{[:a]})
         #{[:a]}))
  (is (= (input-set #{[:a] [:b]} filter #{[:a]})
         #{[:a]}))
  (let [changes #{[:a :b 1] [:g 1 :h 2] [:m :n 3 :x]}]
    (is (= (input-set changes filter #{[:a :* :*]})
           #{[:a :b 1]}))
    (is (= (input-set changes filter #{[:a :b :*]})
           #{[:a :b 1]}))
    (is (= (input-set changes filter #{[:a :b :*] [:m :n :* :*]})
           #{[:a :b 1] [:m :n 3 :x]}))
    (is (= (input-set changes filter #{[:a :b :*] [:g :* :h :*]})
           #{[:a :b 1] [:g 1 :h 2]}))))

(deftest test-propagate?
  (is (propagate? {:change {:updated #{[:a]}}}
                  (with-propagator #{[:a]})))
  (is (propagate? {:change {:updated #{[:a]} :added #{[:b]}}}
                  (with-propagator #{[:a]})))
  (is (propagate? {:change {:updated #{[:a]} :added #{[:b]}}}
                  (with-propagator #{[:a]})))
  (is (propagate? {:change {:updated #{[:a :b :c]}}}
                  (with-propagator #{[:a :*]})))
  (is (not (propagate? {:change {:updated #{[:a :b :c]}}}
                       (with-propagator #{[:b :*]})))))

(deftest test-derive-phase
  (let [double-sum-fn (fn [_ input] (* 2 (reduce + (input-vals input))))]
    (let [state {:change {:updated #{[:a]}}
                 :old {:data-model {:a 0}}
                 :new {:data-model {:a 2}}
                 :dataflow {:derive [{:fn double-sum-fn :in (with-propagator #{[:a]}) :out [:b]}]}
                 :context {}}]
      (is (= (derive-phase state)
             (-> state
                 (assoc-in [:change :added] #{[:b]})
                 (assoc-in [:new :data-model :b] 4)))))
    (let [state {:change {:updated #{[:a]}}
                 :old {:data-model {:a 0}}
                 :new {:data-model {:a 2}}
                 :dataflow {:derive [{:fn double-sum-fn :in (with-propagator #{[:a]}) :out [:b]}
                                     {:fn double-sum-fn :in (with-propagator #{[:a]}) :out [:c]}]}
                 :context {}}]
      (is (= (derive-phase state)
             (-> state
                 (assoc-in [:change :added] #{[:b] [:c]})
                 (update-in [:new :data-model] assoc :b 4 :c 4)))))
    (testing "returned maps record change"
      (let [d (fn [_ input] {:x {:y 11}})
            state {:change {:updated #{[:a]}}
                   :old {:data-model {:a 0 :b {:c {:x {:y 10 :z 15}}}}}
                   :new {:data-model {:a 2 :b {:c {:x {:y 10 :z 15}}}}}
                   :dataflow {:derive [{:fn d :in (with-propagator #{[:a]}) :out [:b :c]}]}
                   :context {}}]
        (is (= (derive-phase state)
               (merge state
                      {:change {:updated #{[:a] [:b :c :x :y]} :removed #{[:b :c :x :z]}}
                       :new {:data-model {:a 2 :b {:c {:x {:y 11}}}}}})))))))

(deftest test-continue-phase
  (let [continue-fn (fn [input] [{msg/topic :x msg/type :y :value (single-val input)}])
        state {:change {:updated #{[:a]}}
               :old {:data-model {:a 0}}
               :new {:data-model {:a 2}}
               :dataflow {:continue #{{:fn continue-fn :in (with-propagator #{[:a]})}}}
               :context {}}]
    (is (= (continue-phase state)
           (assoc-in state [:new :continue] [{msg/topic :x msg/type :y :value 2}])))))

(deftest test-effect-phase
  (let [output-fn (fn [input] [{msg/topic :x msg/type :y :value (single-val input)}])
        state {:change {:updated #{[:a]}}
               :old {:data-model {:a 0}}
               :new {:data-model {:a 2}}
               :dataflow {:effect #{{:fn output-fn :in (with-propagator #{[:a]})}}}
               :context {}}]
    (is (= (effect-phase state)
           (assoc-in state [:new :effect] [{msg/topic :x msg/type :y :value 2}])))))

(deftest test-emit-phase
  (let [emit-fn (fn [k] (fn [input] [{k {:inputs (input-map input)
                                       :updates (updated-map input)}}]))
        emit (fn [x] (set (get-in x [:new :emit])))
        state {:old {:data-model {:a 0
                                  :b 11
                                  :c {1 {:x 6 :y 5 :z 2}
                                      2 {:x 4 :y 7 :z 3}
                                      3 {:x 8 :y 1 :z 4}}}}
               :new {:data-model {:a 0
                                  :b 11
                                  :c {1 {:x 6 :y 5 :z 2}
                                      2 {:x 4 :y 7 :z 3}
                                      3 {:x 8 :y 1 :z 4}}}}
               :dataflow {:emit [{:in (with-propagator #{[:a]})       :fn (emit-fn :one)}
                                 {:in (with-propagator #{[:b]})       :fn (emit-fn :two)}
                                 {:in (with-propagator #{[:c :* :x]}) :fn (emit-fn :three)}
                                 {:in (with-propagator #{[:c :* :y]}) :fn (emit-fn :four)}
                                 {:in (with-propagator #{[:c :*]})    :fn (emit-fn :five)}
                                 {:in (with-propagator #{[:*]})       :fn (emit-fn :six)}]}
               :context {}}]
    (is (= (emit-phase state) state))
    (let [state (-> state
                    (assoc-in [:new :data-model :a] 1)
                    (assoc :change {:updated #{[:a]}})
                    (assoc-in [:dataflow :emit] [{:in (with-propagator #{[:*]})
                                                  :fn (emit-fn :six)}]))]
      (is (= (emit (emit-phase state))
             #{{:six {:inputs {[:a] 1
                               [:b] 11
                               [:c] {1 {:x 6 :y 5 :z 2}
                                     2 {:x 4 :y 7 :z 3}
                                     3 {:x 8 :y 1 :z 4}}}
                      :updates {[:a] 1}}}})))
    (let [state (-> state
                    (assoc :change {:updated #{[:a]}})
                    (assoc-in [:new :data-model :a] 1))]
      (is (= (emit (emit-phase state))
             #{{:one {:inputs {[:a] 1}
                      :updates {[:a] 1}}}})))
    (let [state (-> state
                    (assoc :change {:updated #{[:c 1 :z]}})
                    (assoc-in [:new :data-model :c 1 :z] 9))]
      (is (= (emit (emit-phase state))
             #{{:five {:inputs {[:c 1] {:x 6 :y 5 :z 9}
                                [:c 2] {:x 4 :y 7 :z 3}
                                [:c 3] {:x 8 :y 1 :z 4}}
                       :updates {[:c 1 :z] 9}}}
               {:six {:inputs {[:a] 0
                               [:b] 11
                               [:c] {1 {:x 6 :y 5 :z 9}
                                     2 {:x 4 :y 7 :z 3}
                                     3 {:x 8 :y 1 :z 4}}}
                      :updates {[:c 1 :z] 9}}}})))
    (let [state (-> state
                    (assoc :change {:updated #{[:c 1 :x]}})
                    (assoc-in [:new :data-model :c 1 :x] 9))]
      (is (= (emit (emit-phase state))
             #{{:three {:inputs {[:c 1 :x] 9
                                 [:c 2 :x] 4
                                 [:c 3 :x] 8}
                        :updates {[:c 1 :x] 9}}}})))
    (let [state (-> state
                    (assoc :change {:updated #{[:c 1 :x] [:c 2 :y]}})
                    (assoc-in [:new :data-model :c 1 :x] 9)
                    (assoc-in [:new :data-model :c 2 :y] 15))]
      (is (= (emit (emit-phase state))
             #{{:three {:inputs {[:c 1 :x] 9
                                 [:c 2 :x] 4
                                 [:c 3 :x] 8}
                        :updates {[:c 1 :x] 9}}}
               {:four {:inputs {[:c 1 :y] 5
                                [:c 2 :y] 15
                                [:c 3 :y] 1}
                       :updates {[:c 2 :y] 15}}}})))
    (let [state (-> state
                    (assoc :change {:updated #{[:a] [:c 1 :y] [:c 2 :z]}})
                    (assoc-in [:new :data-model :a] 21)
                    (assoc-in [:new :data-model :c 1 :y] 22)
                    (assoc-in [:new :data-model :c 2 :z] 23))]
      (is (= (emit (emit-phase state))
             #{{:one {:inputs {[:a] 21}
                      :updates {[:a] 21}}}
               {:four {:inputs {[:c 1 :y] 22
                                [:c 2 :y] 7
                                [:c 3 :y] 1}
                       :updates {[:c 1 :y] 22}}}
               {:five {:inputs {[:c 1] {:x 6 :y 22 :z 2}
                                [:c 2] {:x 4 :y 7 :z 23}
                                [:c 3] {:x 8 :y 1 :z 4}}
                       :updates {[:c 2 :z] 23}}}
               {:six {:inputs {[:a] 21
                               [:b] 11
                               [:c] {1 {:x 6 :y 22 :z 2}
                                     2 {:x 4 :y 7 :z 23}
                                     3 {:x 8 :y 1 :z 4}}}
                      :updates {[:c 2 :z] 23}}}})))))

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


;; Complete dataflow tests
;; ================================================================================

(defn inc-t [old-value message]
  (inc old-value))

(defn sum-d [_ input]
  (reduce + (input-vals input)))

(defn double-d [_ input]
  (* 2 (single-val input)))

(defn min-c [n]
  (fn [input]
    (when (< (single-val input) n)
      [{msg/topic [:a] msg/type :inc}])))

(def flows
  {:one-derive     {:transform [[:inc [:a] inc-t]]
                    :derive #{[#{[:a]} [:b] double-d]}}

   :identity       {:transform [[:id [:a] (fn [old-value message] old-value)]]
                    :effect #{{:fn (comp vector input-map)
                               :in (with-propagator #{[:a]} (constantly true))}}}

   :continue-to-10 {:input-adapter (fn [m] {:out (msg/topic m) :key (msg/type m)})
                    :transform [{:key :inc :out [:a] :fn inc-t}]
                    :derive #{{:fn double-d :in #{[:a]} :out [:b]}}
                    :continue #{{:fn (min-c 10) :in #{[:b]}}}}

   :everything {:input-adapter (fn [m] {:out (msg/topic m) :key (msg/type m)})
                :transform [{:out [:a] :key :inc :fn inc-t}]
                :derive    #{{:fn double-d :in #{[:a]} :out [:b]}
                             {:fn sum-d :in #{[:a]} :out [:c]}
                             {:fn sum-d :in #{[:b] [:c]} :out [:d]}}
                :continue  #{{:fn (min-c 10) :in #{[:d]}}}
                :effect    #{{:fn (comp vector input-map) :in #{[:d]}}}
                :emit      [[#{[:d]} (comp vector input-vals)]]}

   :always-emit {:input-adapter (fn [m] {:out (msg/topic m) :key (msg/type m)})
                 :transform [{:key :inc :fn inc-t         :out [:a]}]
                 :derive    #{{:fn double-d :in #{[:a]}   :out [:b]}
                              {:fn sum-d :in #{[:a]}      :out [:c]}
                              {:fn sum-d :in #{[:b] [:c]} :out [:d]}}
                 :continue  #{{:fn (min-c 10) :in #{[:d]}}}
                 :effect    #{{:fn (comp vector input-map) :in #{[:d]}}}
                 :emit      [{:in #{[:a]} :fn (fn [i] [[:always1 (input-map i)]]) :mode :always}
                             {:in #{[:a]} :fn (fn [i] [[:always2 (input-map i)]]) :mode :always}
                             [#{[:d] [:a]} (fn [i] [[:order3 (input-map i)]])]
                             [#{[:a]} (fn [i] [[:order4 (input-map i)]])]]}})

(defn flow [k]
  (build (k flows)))

(deftest test-flow-phases-step
  (is (= (:new (flow-phases-step {:old {:data-model {:a 1}}
                                  :new {:data-model {:a 1}}
                                  :dataflow (flow :one-derive)
                                  :context {:message {:out [:a] :key :inc}}}
                                 (flow :one-derive)
                                 {:out [:a] :key :inc}))
         {:data-model {:a 2 :b 4}}))
  (is (= (:new (flow-phases-step {:new {:data-model {:a 0}}
                                  :old {:data-model {:a 0}}
                                  :dataflow (flow :continue-to-10)
                                  :context {:message {msg/topic [:a] msg/type :inc}}}
                                 (flow :continue-to-10)
                                 {msg/topic [:a] msg/type :inc}))
         {:data-model {:a 1 :b 2}
          :continue [{msg/topic [:a] msg/type :inc}]}))
  (is (= (:new (flow-phases-step {:new {:data-model {:a 0}}
                                  :old {:data-model {:a 0}}
                                  :dataflow (flow :everything)
                                  :context {:message {msg/topic [:a] msg/type :inc}}}
                                 (flow :everything)
                                 {msg/topic [:a] msg/type :inc}))
         {:data-model {:a 1 :b 2 :c 1 :d 3}
          :continue [{msg/topic [:a] msg/type :inc}]})))

(deftest test-run-flow-phases
  (is (= (:new (run-flow-phases {:old {:data-model {:a 0}}
                                 :new {:data-model {:a 0}}
                                 :dataflow (flow :one-derive)
                                 :context {:message {:out [:a] :key :inc}}}
                                (flow :one-derive)
                                {:out [:a] :key :inc}))
         {:data-model {:a 1 :b 2}}))
  (is (= (:new (run-flow-phases {:new {:data-model {:a 0}}
                                 :old {:data-model {:a 0}}
                                 :dataflow (flow :continue-to-10)
                                 :context {:message {msg/topic [:a] msg/type :inc}}}
                                (flow :continue-to-10)
                                {msg/topic [:a] msg/type :inc}))
         {:data-model {:a 5 :b 10}}))
  (is (= (:new (run-flow-phases {:new {:data-model {:a 0}}
                                 :old {:data-model {:a 0}}
                                 :dataflow (flow :everything)
                                 :context {:message {msg/topic [:a] msg/type :inc}}}
                                (flow :everything)
                                {msg/topic [:a] msg/type :inc}))
         {:data-model {:a 4 :b 8 :c 4 :d 12}})))

(deftest test-run
  (is (= (run {:data-model {:a 0}} (flow :one-derive) {:out [:a] :key :inc})
         {:data-model {:a 1 :b 2}}))
  (is (= (run {:data-model {:a 0}} (flow :continue-to-10) {msg/topic [:a] msg/type :inc})
         {:data-model {:a 5 :b 10}}))
  (is (= (run {:data-model {:a 0}}
              (flow :everything)
              {msg/topic [:a] msg/type :inc})
         {:data-model {:a 4 :b 8 :c 4 :d 12}
          :effect [{[:d] 12}]
          :emit [[12]]}))
  (is (= (run {:data-model {:a 0}}
              (flow :always-emit)
              {msg/topic [:a] msg/type :inc})
         {:data-model {:a 4 :b 8 :c 4 :d 12}
          :effect [{[:d] 12}]
          :emit [[:always1 {[:a] 4}]
                 [:always2 {[:a] 4}]
                 [:order3 {[:a] 4 [:d] 12}]]}))
  (testing "custom propagator works"
    (is (= (run {:data-model {:a 1}}
                (flow :identity)
                {:out [:a] :key :id})
           {:data-model {:a 1}
            :effect [{[:a] 1}]}))))



;; Ported tests
;; ================================================================================

(deftest test-dataflows
  (let [dataflow (build {:transform [[:inc [:x] inc-t]]
                         :derive    #{{:fn sum-d :in #{[:x]}      :out [:a]}
                                      {:fn sum-d :in #{[:x] [:a]} :out [:b]}
                                      {:fn sum-d :in #{[:b]}      :out [:c]}
                                      {:fn sum-d :in #{[:a]}      :out [:d]}
                                      {:fn sum-d :in #{[:c] [:d]} :out [:e]}}})]
    (is (= (run {:data-model {:x 0}} dataflow {:out [:x] :key :inc})
           {:data-model {:x 1 :a 1 :b 2 :d 1 :c 2 :e 3}})))

  (let [dataflow (build {:transform [[:inc [:x] inc-t]]
                         :derive    #{{:fn sum-d :in #{[:x]}           :out [:a]}
                                      {:fn sum-d :in #{[:a]}           :out [:b]}
                                      {:fn sum-d :in #{[:a]}           :out [:c]}
                                      {:fn sum-d :in #{[:c]}           :out [:d]}
                                      {:fn sum-d :in #{[:c]}           :out [:e]}
                                      {:fn sum-d :in #{[:d] [:e]}      :out [:f]}
                                      {:fn sum-d :in #{[:a] [:b] [:f]} :out [:g]}
                                      {:fn sum-d :in #{[:g]}           :out [:h]}
                                      {:fn sum-d :in #{[:g] [:f]}      :out [:i]}
                                      {:fn sum-d :in #{[:i] [:f]}      :out [:j]}
                                      {:fn sum-d :in #{[:h] [:g] [:j]} :out [:k]}}})]
    (is (= (run {:data-model {:x 0}} dataflow {:out [:x] :key :inc})
           {:data-model {:x 1 :a 1 :b 1 :c 1 :d 1 :e 1 :f 2 :g 4 :h 4 :i 6 :j 8 :k 16}})))

  (let [dataflow (build {:transform [[:inc [:x :* :y :* :b] inc-t]]
                         :derive    [{:fn sum-d :in #{[:x :* :y :* :b]} :out [:sum :b]}]})]
    (is (= (run {:data-model {:x {0 {:y {0 {:a 1
                                            :b 5}}}
                                  1 {:y {0 {:a 1}
                                         1 {:b 2}}}}
                              :sum {:b 7
                                    :a 2}}}
                dataflow
                {:out [:x 1 :y 1 :b] :key :inc})
           {:data-model {:x {0 {:y {0 {:a 1
                                       :b 5}}}
                             1 {:y {0 {:a 1}
                                    1 {:b 3}}}}
                         :sum {:a 2
                               :b 8}}}))))

(deftest test-multiple-deep-changes
  (let [results (atom nil)
        t (fn [state message]
            (-> state
                (update-in [:c] (fnil inc 0))
                (update-in [:d] (fnil inc 0))))
        e (fn [inputs]
            (reset! results inputs)
            [])
        dataflow (build {:transform [[:a [:b] t]]
                         :emit [[#{[:* :*]} e]]})]
    (is (= (run {:data-model {:b {}}} dataflow {:key :a :out [:b]})
           {:data-model {:b {:c 1 :d 1}}
            :emit []}))
    (is (= @results
           {:added #{[:b :c] [:b :d]}
            :input-paths #{[:* :*]}
            :message {:key :a, :out [:b]}
            :new-model {:b {:c 1, :d 1}}
            :old-model {:b {}}
            :removed #{}
            :updated #{}
            :mode nil
            :processed-inputs nil}))))

(deftest test-removed-inputs
  (is (= (removed-inputs {:updated #{}
                          :added #{}
                          :removed #{[:b :counter :d]}
                          :input-paths #{[:*]}
                          :old-model {:b {:counter {:d 1, :c 1}}, :a {:counter {:b 1, :a 1}}}
                          :new-model {:b {:counter {:c 1}}, :a {:counter {:b 1, :a 1}}}
                          :message {:io.pedestal.app.messages/topic [:b :counter]
                                    :io.pedestal.app.messages/type :dissoc, :key :d}})
         {[:b] {:counter {:c 1}}})))
