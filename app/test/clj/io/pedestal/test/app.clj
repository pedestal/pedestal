; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.test.app
  (:require [io.pedestal.app.protocols :as p]
            [io.pedestal.app.messages :as msg]
            [io.pedestal.app.tree :as tree]
            [io.pedestal.app.dataflow :as dataflow]
            [io.pedestal.app.render :as render])
  (:use io.pedestal.app
        io.pedestal.app.util.test
        clojure.test
        [io.pedestal.app.query :only [q]]))

(refer-privates io.pedestal.app filter-deltas)

(defn input->emitter-output
  "Given a sequence of states return a sequence of maps with :input
  and :emitter."
  [states]
  (mapv (fn [x] {:input (:io.pedestal.app/input x)
                :emitter (set (:io.pedestal.app/emitter-deltas x))}) states))


;; Simplest possible application
;; ================================================================================

(defn number-model [old message]
  (case (msg/type message)
    :io.pedestal.app.messages/init (:value message)
    (:n message)))

(def simplest-possible-app
  {:models {:model-a {:init 0 :fn number-model}}})

(defn standardize-results [results]
  (let [tree (atom tree/new-app-model)]
    (mapv (fn [r]
            (let [d (:io.pedestal.app/emitter-deltas r)
                  old-tree @tree
                  new-tree (swap! tree tree/apply-deltas d)]
              (assoc r :io.pedestal.app/emitter-deltas (tree/since-t new-tree (tree/t old-tree)))))
          results)))

(deftest test-simplest-possible-app
  (let [app (build simplest-possible-app)
        results (run-sync! app [{::msg/topic :model-a :n 42}] :begin :default)
        results (standardize-results results)]
    (is (not (nil? app)))
    (is (= (count results) 4))
    (is (= (first (drop 2 results))
           {:io.pedestal.app/input {::msg/topic :model-a
                                    msg/type msg/init
                                    :value 0}
            :io.pedestal.app/subscriptions [[]]
            :data-model {:model-a 0}
            :io.pedestal.app/emitter-deltas [[:node-create [] :map]
                                             [:node-create [:model-a] :map]
                                             [:value [:model-a] nil 0]]}))
    (is (= (last results)
           {:io.pedestal.app/input {::msg/topic :model-a :n 42}
            :data-model {:model-a 42}
            :io.pedestal.app/subscriptions [[]]
            :io.pedestal.app/emitter-deltas [[:value [:model-a] 0 42]]}))
    (is (= (input->emitter-output results)
           [{:input nil :emitter #{}}
            {:input {::msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{}}
            {:input {::msg/topic :model-a
                     msg/type msg/init
                     :value 0}
             :emitter #{[:node-create [] :map]
                        [:node-create [:model-a] :map]
                        [:value [:model-a] nil 0]}}
            {:input {::msg/topic :model-a :n 42}
             :emitter #{[:value [:model-a] 0 42]}}]))))


;; Two models + defaults
;; ================================================================================

(def two-models-app
  {:models {:model-a {:init 0 :fn number-model}
            :model-b {:init 0 :fn number-model}}})

(deftest test-two-models-app
  (let [app (build two-models-app)
        results (run-sync! app [{::msg/topic :model-a :n 42}
                                {::msg/topic :model-b :n 11}
                                {::msg/topic :model-a :n 3}]
                            :begin :default)
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input nil :emitter #{}}
            {:input {::msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{}}
            {:input {::msg/topic :model-a
                     msg/type msg/init
                     :value 0}
             :emitter #{[:node-create [] :map]
                        [:node-create [:model-a] :map]
                        [:value [:model-a] nil 0]}}
            {:input {::msg/topic :model-b
                     msg/type msg/init
                     :value 0}
             :emitter #{[:node-create [:model-b] :map]
                        [:value [:model-b] nil 0]}}
            {:input {::msg/topic :model-a :n 42}
             :emitter #{[:value [:model-a] 0 42]}}
            {:input {::msg/topic :model-b :n 11}
             :emitter #{[:value [:model-b] 0 11]}}
            {:input {::msg/topic :model-a :n 3}
             :emitter #{[:value [:model-a] 42 3]}}]))))


;; Two models and two views + defaults
;; ================================================================================

(defn sum
  ([_ _ _ n]
     n)
  ([state inputs]
     (let [ns (keep :new (vals inputs))]
       (if (some #(= % :NaN) ns)
         :NaN
         (apply + ns)))))

(defn divider [dividend divisor]
  (fn [state inputs]
    (let [dividend (:new (get inputs dividend))
          divisor (:new (get inputs divisor))]
      (if (== divisor 0)
        :NaN
        (double (/ dividend divisor))))))

(defn half [state input-name old new]
  (if (= new :NaN)
    :NaN
    (double (/ new 2))))

(def two-views-app
  {:models  {:model-a {:init 0 :fn number-model}
             :model-b {:init 0 :fn number-model}}
   :views   {:view-sum  {:fn sum  :input #{:model-a :model-b}}
             :view-half {:fn half :input #{:model-b}}}})

(deftest test-two-views-app
  (let [app (build two-views-app)
        results (run-sync! app [{::msg/topic :model-a :n 42}
                                {::msg/topic :model-b :n 10}
                                {::msg/topic :model-a :n 3}]
                            :begin :default)
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input nil :emitter #{}}
            {:input {::msg/topic msg/app-model msg/type :subscribe :paths [[]]}
             :emitter #{}}
            {:input {::msg/topic :model-a msg/type msg/init :value 0}
             :emitter #{[:node-create [] :map]
                        [:node-create [:model-a] :map]
                        [:value [:model-a] nil 0]
                        [:node-create [:view-sum] :map]
                        [:value [:view-sum] nil 0]}}
            {:input {::msg/topic :model-b msg/type msg/init :value 0}
             :emitter #{[:node-create [:view-half] :map]
                        [:value [:view-half] nil 0.0]
                        [:node-create [:model-b] :map]
                        [:value [:model-b] nil 0]}}
            {:input {::msg/topic :model-a :n 42}
             :emitter #{[:value [:model-a] 0 42]
                        [:value [:view-sum] 0 42]}}
            {:input {::msg/topic :model-b :n 10}
             :emitter #{[:value [:model-b] 0 10]
                        [:value [:view-half] 0.0 5.0]
                        [:value [:view-sum] 42 52]}}
            {:input {::msg/topic :model-a :n 3}
             :emitter #{[:value [:model-a] 42 3]
                        [:value [:view-sum] 52 13]}}]))))

;; In the next few tests, we will use the application framework to
;; calculate the square root of a number. We will encode Heron's
;; method of iteratively approximating the square root until the
;; disired accuracy is achieved.

;; This will test several advanced features of the framework,
;; including:
;;
;; - Views which have other views as input
;; - Views which generate feedback
;; - Recursive message processing within a single transaction

(defn good-enough? [state inputs]
  (let [{:keys [accuracy half]} inputs
        [old-acc new-acc] ((juxt :old :new) accuracy)
        [old-guess new-guess] ((juxt :old :new) half)]
    (if (some #(= % :NaN) [new-acc new-guess old-guess])
      {:good-enough? false :new-guess new-guess}
      (let [good-enough? (and (> new-acc (Math/abs (- new-guess old-guess)))
                              (= old-acc new-acc))
            new-guess (cond (not= old-acc new-acc) (- new-guess new-acc)
                            :else new-guess)]
        {:good-enough? good-enough? :new-guess new-guess}))))

(defn continue-calc [view-name _ n]
  (when (not (or (:good-enough? n)
                 (= (:new-guess n) :NaN)))
    [{::msg/topic :guess :n (:new-guess n)}]))


;; Calculate square root using Heron's method
;; ================================================================================
;; this will be the first time that test recursion based on an feedback function

(def square-root-app
  {:models  {:guess    {:init 0 :fn number-model}
             :x        {:init 0 :fn number-model}
             :accuracy {:init 0 :fn number-model}}
   :views   {:divide       {:fn (divider :x :guess) :input #{:x :guess}}
             :sum          {:fn sum :input #{:guess :divide}}
             :half         {:fn half :input #{:sum}}
             :good-enough? {:fn good-enough? :input #{:half :accuracy}}}
   :feedback  {:good-enough? continue-calc}
   :emitters {:answer {:fn default-emitter-fn :input #{:x :half}}}})

(deftest test-square-root
  (let [app (build square-root-app)
        results (run-sync! app [{::msg/topic :accuracy :n 0.000001}
                                {::msg/topic :x :n 42}
                                {::msg/topic :guess :n 7}]
                            :begin :default)
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input nil :emitter #{}}
            {:input {::msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:x] :map]
                        [:node-create [:half] :map]}}
            {:input {::msg/topic :guess
                     msg/type msg/init
                     :value 0}
             :emitter #{[:value [:half] nil :NaN]}}
            {:input {::msg/topic :x
                     msg/type msg/init
                     :value 0}
             :emitter #{[:value [:x] nil 0]}}
            {:input {::msg/topic :accuracy
                     msg/type msg/init
                     :value 0}
             :emitter #{}}
            {:input {::msg/topic :accuracy :n 0.000001} :emitter #{}}
            {:input {::msg/topic :x :n 42} :emitter #{[:value [:x] 0 42]}}
            {:input {:n 7 ::msg/topic :guess}
             :emitter #{[:value [:half] :NaN 6.48074069840786]}}]))))

(deftest test-square-root-modify-inputs
  (let [app (build square-root-app)
        results (run-sync! app [{::msg/topic :accuracy :n 0.000001}
                                {::msg/topic :x :n 42}
                                {::msg/topic :guess :n 7}
                                {::msg/topic :x :n 50}]
                            :begin :default)
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input nil :emitter #{}}
            {:input {::msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:x] :map]
                        [:node-create [:half] :map]}}
            {:input {::msg/topic :guess
                     msg/type msg/init
                     :value 0}
             :emitter #{[:value [:half] nil :NaN]}}
            {:input {::msg/topic :x
                     msg/type msg/init
                     :value 0}
             :emitter #{[:value [:x] nil 0]}}
            {:input {::msg/topic :accuracy
                     msg/type msg/init
                     :value 0}
             :emitter #{}}
            {:input {::msg/topic :accuracy :n 0.000001} :emitter #{}}
            {:input {::msg/topic :x :n 42} :emitter #{[:value [:x] 0 42]}}
            {:input {:n 7 ::msg/topic :guess}
             :emitter #{[:value [:half] :NaN 6.48074069840786]}}
            {:input {:n 50 ::msg/topic :x}
             :emitter #{[:value [:half] 6.48074069840786 7.0710678118654755]
                        [:value [:x] 42 50]}}]))))


;; Test multiple dependent views which depend on one model
;; ================================================================================

(defn square [state input-name old new]
  (if (= new :NaN)
    :NaN
    (* new new)))

(def dependent-views-app
  {:models  {:x      {:init 0 :fn number-model}}
   :views   {:half   {:fn half :input #{:x}}
             :square {:fn square :input #{:x}}
             :sum    {:fn sum :input #{:half :x :square}}}
   :emitters {:answer {:fn default-emitter-fn :input #{:x :sum}}}})

(deftest test-dependent-views-which-depend-on-one-model
  (let [app (build dependent-views-app)
        results (run-sync! app [{::msg/topic :x :n 42}
                                {::msg/topic :x :n 12}]
                            :begin :default)
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input nil :emitter #{}}
            {:input {::msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:x] :map]
                        [:node-create [:sum] :map]}}
            {:input {::msg/topic :x
                     msg/type msg/init
                     :value 0}
             :emitter #{[:value [:x] nil 0]
                        [:value [:sum] nil 0.0]}}
            {:input {::msg/topic :x :n 42}
             :emitter #{[:value [:x] 0 42]
                        [:value [:sum] 0.0 1827.0]}}
            {:input {::msg/topic :x :n 12}
             :emitter #{[:value [:x] 42 12]
                        [:value [:sum] 1827.0 162.0]}}]))))

(def two-views-with-same-input-old-values
  {:models  {:x {:init 0 :fn number-model}}
   :views   {:a {:fn (fn [_ _ o _] o) :input #{:x}}
             :b {:fn (fn [_ _ o _] o) :input #{:x}}}
   :emitters {:answer {:fn default-emitter-fn :input #{:a :b}}}})

(deftest test-two-views-with-same-input-old-values
  (let [app (build two-views-with-same-input-old-values)
        results (run-sync! app [{::msg/topic :x :n 1}
                                {::msg/topic :x :n 2}]
                            :begin :default)
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input nil :emitter #{}}
            {:input {::msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:a] :map]
                        [:node-create [:b] :map]}}
            {:input {::msg/topic :x
                     msg/type msg/init
                     :value 0}
             :emitter #{}}
            {:input {::msg/topic :x :n 1}
             :emitter #{[:value [:a] nil 0]
                        [:value [:b] nil 0]}}
            {:input {::msg/topic :x :n 2}
             :emitter #{[:value [:a] 0 1]
                        [:value [:b] 0 1]}}]))))

(def two-views-with-same-input-new-values
  {:models  {:x {:init 0 :fn number-model}}
   :views   {:a {:fn (fn [_ _ _ n] n) :input #{:x}}
             :b {:fn (fn [_ _ _ n] n) :input #{:x}}}
   :emitters {:answer {:fn default-emitter-fn :input #{:a :b}}}})

(deftest test-two-views-with-same-input-new-values
  (let [app (build two-views-with-same-input-new-values)
        results (run-sync! app [{::msg/topic :x :n 1}
                                {::msg/topic :x :n 2}]
                            :begin :default)
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input nil :emitter #{}}
            {:input {::msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:a] :map]
                        [:node-create [:b] :map]}}
            {:input {::msg/topic :x
                     msg/type msg/init
                     :value 0}
             :emitter #{[:value [:a] nil 0]
                        [:value [:b] nil 0]}}
            {:input {::msg/topic :x :n 1}
             :emitter #{[:value [:a] 0 1]
                        [:value [:b] 0 1]}}
            {:input {::msg/topic :x :n 2}
             :emitter #{[:value [:a] 1 2]
                        [:value [:b] 1 2]}}]))))


;; Model output
;; ================================================================================

(defn echo-output [service-name]
  (fn [message old-model new-model]
    [{::msg/topic {:service service-name} :n new-model}]))

(defn capture-queue [n queue-name app state]
  (when (pos? n)
    (p/take-message (queue-name app)
                    (fn [message]
                      (swap! state conj message)
                      (capture-queue (dec n) queue-name app state)))))

(deftest test-output-app
  (let [expected [{:input nil :emitter #{}}
                  {:input {::msg/topic msg/app-model
                           msg/type :subscribe
                           :paths [[]]}
                   :emitter #{[:node-create [] :map]
                              [:node-create [:x] :map]
                              [:node-create [:sum] :map]}}
                  {:input {::msg/topic :x
                           msg/type msg/init
                           :value 0}
                   :emitter #{[:value [:x] nil 0]
                              [:value [:sum] nil 0.0]}}
                  {:input {::msg/topic :x :n 42}
                   :emitter #{[:value [:x] 0 42]
                              [:value [:sum] 0.0 1827.0]}}
                  {:input {::msg/topic :x :n 12}
                   :emitter #{[:value [:x] 42 12]
                              [:value [:sum] 1827.0 162.0]}}]
        output-app {:models   {:x      {:init 0 :fn number-model}}
                    :views    {:half   {:fn half :input #{:x}}
                               :square {:fn square :input #{:x}}
                               :sum    {:fn sum :input #{:half :x :square}}}
                    :output   {:x (echo-output :s)}
                    :emitters {:answer {:fn default-emitter-fn :input #{:x :sum}}}}]
    (testing "with input from model"
      (let [output-state (atom [])
            app (build output-app)
            _ (capture-queue 3 :output app output-state)
            results (run-sync! app [{::msg/topic :x :n 42}
                                    {::msg/topic :x :n 12}]
                                :begin :default)
            results (standardize-results results)]
        (is (= @output-state
               [{::msg/topic {:service :s} :n 0}
                {::msg/topic {:service :s} :n 42}
                {::msg/topic {:service :s} :n 12}]))
        (is (= (input->emitter-output results) expected))))
    (testing "with input from view"
      (let [output-state (atom [])
            app (build (merge output-app
                              {:output {:half (echo-output :s)}}))
            _ (capture-queue 3 :output app output-state)
            results (run-sync! app [{::msg/topic :x :n 42}
                                    {::msg/topic :x :n 12}]
                                :begin :default)
            results (standardize-results results)]
        (is (= @output-state
               [{::msg/topic {:service :s} :n 0.0}
                {::msg/topic {:service :s} :n 21.0}
                {::msg/topic {:service :s} :n 6.0}]))
        (is (= (input->emitter-output results) expected))))))

(deftest test-new-output-app
  (let [output-app {:version 2
                    :transform [{msg/type :number ::msg/topic [:x]
                                 :fn number-model
                                 :init [{msg/type :number ::msg/topic [:x] :n 0}]}]
                    :derive #{{:in #{[:x]}
                               :out [:half]
                               :fn (fn [x n] (/ n 2.0))
                               :args :single-val}
                              {:in #{[:x]}
                               :out [:square]
                               :fn (fn [x n] (* n n))
                               :args :single-val}
                              ;; test using alternative keys
                              {:in {[:half] :h [:x] :x [:square] :s}
                               :out [:sum]
                               :fn (fn [x nums] (+ (:h nums) (:x nums) (:s nums)))
                               :args :map}}
                    :effect #{{:in #{[:x]} :fn dataflow/input-map}}
                    :emit [[#{[:x] [:sum]} (default-emitter [])]]}]
    (testing "with input from model and direct-to-output"
      (let [output-state (atom [])
            app (build output-app)
            _ (capture-queue 4 :output app output-state)
            results (run-sync! app [{::msg/topic [:x] msg/type :number :n 42}
                                    {::msg/topic msg/output :payload [[:z] 9999]}
                                    {::msg/topic [:x] msg/type :number :n 12}]
                               :begin :default)
            results (standardize-results results)]
        (is (= @output-state
               [[[:x] 0]
                [[:x] 42]
                [[:z] 9999]
                [[:x] 12]]))
        (is (= (set (apply concat (map :io.pedestal.app/emitter-deltas results)))
               #{[:node-create [] :map]
                 [:node-create [:sum] :map]
                 [:value [:sum] nil 0.0]
                 [:node-create [:x] :map]
                 [:value [:x] nil 0]
                 [:value [:sum] 0.0 1827.0]
                 [:value [:x] 0 42]
                 [:value [:sum] 1827.0 162.0]
                 [:value [:x] 42 12]}))))
    (testing "pre processing"
      (letfn [(copy-to-output [message]
                [{::msg/topic msg/output :payload message}
                 message])]
        (let [output-state (atom [])
              output-app (assoc output-app :pre [[:number [:x] copy-to-output]])
              app (build output-app)
              _ (capture-queue 6 :output app output-state)
              results (run-sync! app [{::msg/topic [:x] msg/type :number :n 42}
                                      {::msg/topic [:x] msg/type :number :n 12}]
                                 :begin :default)
              results (standardize-results results)]
          (is (= @output-state
                 [{::msg/topic [:x] msg/type :number :n 0}
                  [[:x] 0]
                  {::msg/topic [:x] msg/type :number :n 42}
                  [[:x] 42]
                  {::msg/topic [:x] msg/type :number :n 12}
                  [[:x] 12]]))
          (is (= (set (apply concat (map :io.pedestal.app/emitter-deltas results)))
                 #{[:node-create [] :map]
                   [:node-create [:sum] :map]
                   [:value [:sum] nil 0.0]
                   [:node-create [:x] :map]
                   [:value [:x] nil 0]
                   [:value [:sum] 0.0 1827.0]
                   [:value [:x] 0 42]
                   [:value [:sum] 1827.0 162.0]
                   [:value [:x] 42 12]})))))))


;; Test with Renderer
;; ================================================================================

(deftest test-with-renderer
  (let [app (build dependent-views-app)
        renderer-state (atom [])]
    (capture-queue 4 :app-model app renderer-state)
    (let [results (run-sync! app [{::msg/topic :x :n 42}
                                  {::msg/topic :x :n 12}]
                              :begin :default)
          results (standardize-results results)]
      (is (= (set @renderer-state)
             #{{::msg/topic msg/app-model
                msg/type :deltas
                :deltas [[:node-create [:sum] :map]
                         [:value [:sum] nil nil]
                         [:node-create [:x] :map]
                         [:value [:x] nil nil]]}
               {::msg/topic msg/app-model
                msg/type :deltas
                :deltas [[:value [:x] 0]
                         [:value [:sum] 0.0]]}
               {::msg/topic msg/app-model
                msg/type :deltas
                :deltas [[:value [:x] 42]
                         [:value [:sum] 1827.0]]}
               {::msg/topic msg/app-model
                msg/type :deltas
                :deltas [[:value [:x] 12]
                         [:value [:sum] 162.0]]}}))
      (is (= (input->emitter-output results)
             [{:input nil :emitter #{}}
              {:input {::msg/topic msg/app-model
                       msg/type :subscribe
                       :paths [[]]}
               :emitter #{[:node-create [] :map]
                          [:node-create [:x] :map]
                          [:node-create [:sum] :map]}}
              {:input {::msg/topic :x
                       msg/type msg/init
                       :value 0}
               :emitter #{[:value [:x] nil 0]
                          [:value [:sum] nil 0.0]}}
              {:input {::msg/topic :x :n 42}
               :emitter #{[:value [:x] 0 42]
                          [:value [:sum] 0.0 1827.0]}}
              {:input {::msg/topic :x :n 12}
               :emitter #{[:value [:x] 42 12]
                          [:value [:sum] 1827.0 162.0]}}])))))


;; Dataflow tests
;; ================================================================================

(def dataflow-test-one
  {:models  {:x {:init 0 :fn number-model}}
   :views   {:a {:fn sum :input #{:x}}
             :b {:fn sum :input #{:x :a}}
             :c {:fn sum :input #{:b}}
             :d {:fn sum :input #{:a}}
             :e {:fn sum :input #{:c :d}}}
   :emitters {:answer {:fn default-emitter-fn :input #{:e}}}})

(deftest test-dataflow-one
  (let [app (build dataflow-test-one)
        results (run-sync! app [{::msg/topic :x :n 1}
                                {::msg/topic :x :n 2}]
                            :begin :default)
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input nil :emitter #{}}
            {:input {::msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:e] :map]}}
            {:input {::msg/topic :x
                     msg/type msg/init
                     :value 0}
             :emitter #{[:value [:e] nil 0]}}
            {:input {::msg/topic :x :n 1}
             :emitter #{[:value [:e] 0 3]}}
            {:input {::msg/topic :x :n 2}
             :emitter #{[:value [:e] 3 6]}}]))))

(def dataflow-test-two
  {:models  {:x {:init 0 :fn number-model}}
   :views   {:a {:fn sum :input #{:x}}
             :b {:fn sum :input #{:a}}
             :c {:fn sum :input #{:a}}
             :d {:fn sum :input #{:c}}
             :e {:fn sum :input #{:c}}
             :f {:fn sum :input #{:d :e}}
             :g {:fn sum :input #{:a :b :f}}
             :h {:fn sum :input #{:g}}
             :i {:fn sum :input #{:g :f}}
             :j {:fn sum :input #{:i :f}}
             :k {:fn sum :input #{:h :g :j}}}
   :emitters {:answer {:fn default-emitter-fn :input #{:k}}}})

(deftest test-dataflow-two
  (let [app (build dataflow-test-two)
        results (run-sync! app [{::msg/topic :x :n 1}
                                {::msg/topic :x :n 2}]
                            :begin :default)
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input nil :emitter #{}}
            {:input {::msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:k] :map]}}
            {:input {::msg/topic :x
                     msg/type msg/init
                     :value 0}
             :emitter #{[:value [:k] nil 0]}}
            {:input {::msg/topic :x :n 1}
             :emitter #{[:value [:k] 0 16]}}
            {:input {::msg/topic :x :n 2}
             :emitter #{[:value [:k] 16 32]}}]))))


;; New App Model Navigation
;; ================================================================================

(deftest test-filter-deltas
  (is (= (filter-deltas {:io.pedestal.app/subscriptions [[:a :b] [:a :j]]}
                        [[:_ [:a]]
                         [:_ [:a :b]]
                         [:_ [:a :b :c]]
                         [:_ [:a :x :c :b]]
                         [:_ [:a :j :y]]])
         [[:_ [:a :b]]
          [:_ [:a :b :c]]
          [:_ [:a :j :y]]])))


;; Application model navigation tests
;; ================================================================================

(def navigation-app
  {:models  {:a {:init 1 :fn number-model}
             :b {:init 2 :fn number-model}
             :c {:init 3 :fn number-model}}
   :emitters {:ea {:fn default-emitter-fn :input #{:a}}
              :eb {:fn default-emitter-fn :input #{:b}}
              :ec {:fn default-emitter-fn :input #{:c}}}
   :navigation {:a [[:a]]
                :b [[:b]]
                :c [[:c]]
                :default :a}})

(defn- partition-sets [coll sizes]
  (loop [partition []
         coll coll
         [x & xs] sizes]
    (if x
      (recur (conj partition (if (= x 1)
                               (first coll)
                               (set (take x coll))))
             (drop x coll)
             xs)
      partition)))

(deftest test-navigation-app
  (testing "only view the default paths"
    (let [app (build navigation-app)
          results (run-sync! app [{::msg/topic :a :n 10}
                                  {::msg/topic :b :n 11}
                                  {::msg/topic :c :n 12}]
                              :begin :default)
          results (standardize-results results)]
      (is (= (partition-sets (input->emitter-output results) [4 1 3 1 1 1])
             [#{{:input nil :emitter #{}}
                {:input {::msg/topic msg/app-model msg/type :add-named-paths :name :a :paths [[:a]]}
                 :emitter #{}}
                {:input {::msg/topic msg/app-model msg/type :add-named-paths :name :c :paths [[:c]]}
                 :emitter #{}}
                {:input {::msg/topic msg/app-model msg/type :add-named-paths :name :b :paths [[:b]]}
                 :emitter #{}}}

              {:input {::msg/topic msg/app-model msg/type :navigate :name :a}
               :emitter #{[:node-create [] :map] [:node-create [:a] :map]}}

              #{{:input {::msg/topic :a msg/type msg/init :value 1}
                 :emitter #{[:value [:a] nil 1]}}
                {:input {::msg/topic :b msg/type msg/init :value 2}
                 :emitter #{}}
                {:input {::msg/topic :c msg/type msg/init :value 3}
                 :emitter #{}}}

              {:input {::msg/topic :a :n 10}
               :emitter #{[:value [:a] 1 10]}}

              {:input {::msg/topic :b :n 11}
               :emitter #{}}

              {:input {::msg/topic :c :n 12}
               :emitter #{}}]))))
  (testing "navigate between paths"
    (let [app (build navigation-app)
          results (run-sync! app [{::msg/topic :a :n 10}
                                  {::msg/topic :b :n 11}
                                  {::msg/topic msg/app-model msg/type :navigate :name :b}
                                  {::msg/topic :b :n 12}
                                  {::msg/topic :c :n 13}
                                  {::msg/topic msg/app-model msg/type :navigate :name :c}
                                  {::msg/topic :c :n 14}
                                  {::msg/topic :a :n 15}
                                  {::msg/topic msg/app-model msg/type :navigate :name :a}]
                             :begin :default)
          results (standardize-results results)]
      (is (= (input->emitter-output results)
             [{:input nil :emitter #{}}
              {:input {::msg/topic msg/app-model msg/type :add-named-paths :name :a :paths [[:a]]}
               :emitter #{}}
              {:input {::msg/topic msg/app-model msg/type :add-named-paths :name :c :paths [[:c]]}
               :emitter #{}}
              {:input {::msg/topic msg/app-model msg/type :add-named-paths :name :b :paths [[:b]]}
               :emitter #{}}

              {:input {::msg/topic msg/app-model msg/type :navigate :name :a}
               :emitter #{[:node-create [] :map] [:node-create [:a] :map]}}

              {:input {::msg/topic :a msg/type msg/init :value 1}
               :emitter #{[:value [:a] nil 1]}}
              {:input {::msg/topic :b msg/type msg/init :value 2}
               :emitter #{}}
              {:input {::msg/topic :c msg/type msg/init :value 3}
               :emitter #{}}

              {:input {::msg/topic :a :n 10} :emitter #{[:value [:a] 1 10]}}
              {:input {::msg/topic :b :n 11} :emitter #{}}

              {:input {::msg/topic msg/app-model msg/type :navigate :name :b}
               :emitter #{[:value [:a] 10 nil]
                          [:node-destroy [:a] :map]
                          [:node-create [:b] :map]
                          [:value [:b] nil 11]}}

              {:input {::msg/topic :b :n 12} :emitter #{[:value [:b] 11 12]}}
              {:input {::msg/topic :c :n 13} :emitter #{}}

              {:input {::msg/topic msg/app-model msg/type :navigate :name :c}
               :emitter #{[:value [:b] 12 nil]
                          [:node-destroy [:b] :map]
                          [:node-create [:c] :map]
                          [:value [:c] nil 13]}}

              {:input {::msg/topic :c :n 14} :emitter #{[:value [:c] 13 14]}}
              {:input {::msg/topic :a :n 15} :emitter #{}}

              {:input {::msg/topic msg/app-model msg/type :navigate :name :a}
               :emitter #{[:value [:c] 14 nil]
                          [:node-destroy [:c] :map]
                          [:node-create [:a] :map]
                          [:value [:a] nil 15]}}])))))

(deftest test-focus
  (let [inc-t (fn [old message] ((fnil inc 0) old))
        flow {:version 2
              :transform [[:inc [:*] inc-t]]
              :focus {:x [[:a] [:b]]
                      :y [[:a] [:c]]
                      :z [[:d]]
                      :default :x}}
        app (build flow)
        results (run-sync! app [{::msg/topic [:a] msg/type :inc}
                                {::msg/topic [:b] msg/type :inc}
                                {::msg/topic [:c] msg/type :inc}
                                {::msg/topic msg/app-model msg/type :set-focus :name :y}
                                {::msg/topic [:a] msg/type :inc}
                                {::msg/topic [:c] msg/type :inc}
                                {::msg/topic [:d] msg/type :inc}
                                {::msg/topic msg/app-model msg/type :navigate :name :z}]
                           :begin :default)
        results (standardize-results results)]
    (is (= (apply concat (map :io.pedestal.app/emitter-deltas results))
           [[:node-create [] :map]
            [:node-create [:a] :map]
            [:value [:a] nil 1]
            [:node-create [:b] :map]
            [:value [:b] nil 1]
            [:value [:b] 1 nil]
            [:node-destroy [:b] :map]
            [:node-create [:c] :map]
            [:value [:c] nil 1]
            [:value [:a] 1 2]
            [:value [:c] 1 2]
            [:value [:a] 2 nil]
            [:node-destroy [:a] :map]
            [:value [:c] 2 nil]
            [:node-destroy [:c] :map]
            [:node-create [:d] :map]
            [:value [:d] nil 1]]))))


;; Navigate using only :subscribe and :unsubscribe
;; ================================================================================

(def subscribe-and-unsubscribe-app
  {:models  {:a {:init 1 :fn number-model}
             :b {:init 2 :fn number-model}
             :c {:init 3 :fn number-model}}
   :emitters {:ea {:fn default-emitter-fn :input #{:a}}
              :eb {:fn default-emitter-fn :input #{:b}}
              :ec {:fn default-emitter-fn :input #{:c}}}})

(deftest test-subscribe-and-unsubscribe-app
  (let [app (build subscribe-and-unsubscribe-app)
        results (run-sync! app
                           [{::msg/topic :a :n 10}
                            {::msg/topic msg/app-model msg/type :subscribe :paths [[:a]]}
                            {::msg/topic :b :n 11}
                            {::msg/topic :c :n 12}
                            {::msg/topic :a :n 13}
                            {::msg/topic msg/app-model msg/type :unsubscribe :paths [[:a]]}
                            {::msg/topic :c :n 14}
                            {::msg/topic msg/app-model msg/type :subscribe :paths [[:b] [:c]]}
                            {::msg/topic :a :n 15}
                            {::msg/topic :b :n 16}
                            {::msg/topic :c :n 17}
                            {::msg/topic msg/app-model msg/type :unsubscribe :paths [[:b]]}
                            {::msg/topic :a :n 18}
                            {::msg/topic :b :n 19}
                            {::msg/topic :c :n 20}]
                           :begin [{::msg/topic msg/app-model msg/type :noop}])
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input nil :emitter #{}}
            {:input {::msg/topic msg/app-model msg/type :noop}
             :emitter #{}}

            {:input {::msg/topic :a msg/type msg/init :value 1}
             :emitter #{}}
            {:input {::msg/topic :b msg/type msg/init :value 2}
             :emitter #{}}
            {:input {::msg/topic :c msg/type msg/init :value 3}
             :emitter #{}}

            {:input {::msg/topic :a :n 10} :emitter #{}}

            {:input {::msg/topic msg/app-model msg/type :subscribe :paths [[:a]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:a] :map]
                        [:value [:a] nil 10]}}

            {:input {::msg/topic :b :n 11} :emitter #{}}
            {:input {::msg/topic :c :n 12} :emitter #{}}
            {:input {::msg/topic :a :n 13} :emitter #{[:value [:a] 10 13]}}

            {:input {::msg/topic msg/app-model msg/type :unsubscribe :paths [[:a]]}
             :emitter #{[:value [:a] 13 nil]
                        [:node-destroy [:a] :map]}}

            {:input {::msg/topic :c :n 14} :emitter #{}}

            {:input {::msg/topic msg/app-model msg/type :subscribe :paths [[:b] [:c]]}
             :emitter #{[:node-create [:b] :map]
                        [:node-create [:c] :map]
                        [:value [:b] nil 11]
                        [:value [:c] nil 14]}}

            {:input {::msg/topic :a :n 15} :emitter #{}}
            {:input {::msg/topic :b :n 16} :emitter #{[:value [:b] 11 16]}}
            {:input {::msg/topic :c :n 17} :emitter #{[:value [:c] 14 17]}}

            {:input {::msg/topic msg/app-model msg/type :unsubscribe :paths [[:b]]}
             :emitter #{[:value [:b] 16 nil]
                        [:node-destroy [:b] :map]}}

            {:input {::msg/topic :a :n 18} :emitter #{}}
            {:input {::msg/topic :b :n 19} :emitter #{}}
            {:input {::msg/topic :c :n 20} :emitter #{[:value [:c] 17 20]}}]))))


;; Compare old and new style apps
;; ================================================================================

(deftest test-old-and-new-two-counters
  (testing "old style app with two counters"
    (let [count-transform (fn [t-state message]
                            (condp = (msg/type message)
                              msg/init (:value message)
                              :inc (update-in (or t-state {}) [(:key message)] inc)
                              t-state))

          a-combine (fn [c-state t-name t-old-val t-new-val]
                      (:a t-new-val))

          b-combine (fn [c-state t-name t-old-val t-new-val]
                      (:b t-new-val))

          counter-emit (fn
                         ([inputs] [{:counter {:a {} :b {}}}])
                         ([inputs changed-inputs]
                            (concat []
                                    (when (changed-inputs :a-combine)
                                      [[:value [:counter :a]
                                        (-> inputs :a-combine :new)]])
                                    (when (changed-inputs :b-combine)
                                      [[:value [:counter :b]
                                        (-> inputs :b-combine :new)]]))))]
      (let [dataflow {:transform {:count-transform {:init {:a 0 :b 0} :fn count-transform}}
                      :combine {:a-combine {:fn a-combine :input #{:count-transform}}
                                :b-combine {:fn b-combine :input #{:count-transform}}}
                      :emit {:counter-emit {:fn counter-emit :input #{:a-combine :b-combine}}}}
            app (build dataflow)
            _ (begin app)
            results (run-sync! app [{::msg/topic :count-transform msg/type :inc :key :a}])
            results (standardize-results results)]
        (is (= (apply concat (map :io.pedestal.app/emitter-deltas results))
               [[:node-create [] :map]
                [:node-create [:counter] :map]
                [:node-create [:counter :a] :map]
                [:node-create [:counter :b] :map]
                [:value [:counter :a] nil 0]
                [:value [:counter :b] nil 0]
                [:value [:counter :a] 0 1]])))))

  (testing "new style app with two counters"
    (let [init-transform (fn [t-state message] (:value message))
          count-transform (fn [t-state message] ((fnil inc 0) t-state))]
      (let [dataflow {:version 2
                      :transform [[:init [:counter :*] init-transform]
                                  {msg/type :inc ::msg/topic [:counter :*] :fn count-transform
                                   :init [{::msg/topic [:counter :a] msg/type :init :value 0}
                                          {::msg/topic [:counter :b] msg/type :init :value 0}]}]
                      :emit [{:in #{[:counter :*]} :fn (default-emitter :root)
                              :init (fn [_] [{:root {:counter {:a {} :b {}}}}])}]}
            app (build dataflow)
            _ (begin app)
            results (run-sync! app [{::msg/topic [:counter :a] msg/type :inc}])
            results (standardize-results results)]
        (is (= (apply concat (map :io.pedestal.app/emitter-deltas results))
               [[:node-create [] :map]
                [:node-create [:root] :map]
                [:node-create [:root :counter] :map]
                [:node-create [:root :counter :a] :map]
                [:node-create [:root :counter :b] :map]
                [:value [:root :counter :a] nil 0]
                [:value [:root :counter :b] nil 0]
                [:value [:root :counter :a] 0 1]])))))

  (testing "shorter version of new style app with two counters"
    (let [count-transform (fn [t-state message] ((fnil inc 0) t-state))]
      (let [dataflow {:version 2
                      :transform [[:inc [:counter :*] count-transform]]
                      :emit [[#{[:counter :*]} (default-emitter [:root :path])]]}
            app (build dataflow)
            _ (begin app)
            results (run-sync! app [{::msg/topic [:counter :a] msg/type :inc}])
            results (standardize-results results)]
        (is (= (apply concat (map :io.pedestal.app/emitter-deltas results))
               [[:node-create [] :map]
                [:node-create [:root] :map]
                [:node-create [:root :path] :map]
                [:node-create [:root :path :counter] :map]
                [:node-create [:root :path :counter :a] :map]
                [:value [:root :path :counter :a] nil 1]]))))))

(deftest test-default-emitter
  (let [count-transform (fn [t-state message] ((fnil inc 0) t-state))
        dissoc-transform (fn [t-state message] (dissoc t-state (:key message)))]
    (let [dataflow {:version 2
                    :transform [[:inc [:* :counter :*] count-transform]
                                [:dissoc [:**] dissoc-transform]]
                    :emit [[#{[:*]} (default-emitter [])]]
                    :focus {:a [[:a]]
                            :b [[:b]]
                            :default :a}}
          messages [{::msg/topic [:a :counter :a] msg/type :inc}
                    {::msg/topic [:a :counter :b] msg/type :inc}
                    {::msg/topic [:b :counter :c] msg/type :inc}
                    {::msg/topic [:b :counter :d] msg/type :inc}
                    {::msg/topic msg/app-model msg/type :navigate :name :b}
                    {::msg/topic [:b :counter] msg/type :dissoc :key :d}
                    {::msg/topic [:b :counter :c] msg/type :inc}
                    {::msg/topic [:b] msg/type :dissoc :key :counter}
                    {::msg/topic msg/app-model msg/type :navigate :name :a}]]
      (let [app (build dataflow)
            _ (begin app)
            results (run-sync! app messages)
            results (standardize-results results)]
        (is (= (apply concat (map :io.pedestal.app/emitter-deltas results))
               [[:node-create [] :map]
                [:node-create [:a] :map]
                [:value [:a] nil {:counter {:a 1}}]
                [:value [:a] {:counter {:a 1}} {:counter {:a 1 :b 1}}]
                [:value [:a] {:counter {:a 1 :b 1}} nil]
                [:node-destroy [:a] :map]
                [:node-create [:b] :map]
                [:value [:b] nil {:counter {:c 1 :d 1}}]
                [:value [:b] {:counter {:c 1 :d 1}} {:counter {:c 1}}]
                [:value [:b] {:counter {:c 1}} {:counter {:c 2}}]
                [:value [:b] {:counter {:c 2}} {}]
                [:value [:b] {} nil]
                [:node-destroy [:b] :map]
                [:node-create [:a] :map]
                [:value [:a] nil {:counter {:a 1 :b 1}}]])))
      (let [app (build (assoc dataflow :emit [[#{[:* :*]} (default-emitter [])]]))
            _ (begin app)
            results (run-sync! app messages)
            results (standardize-results results)]
        (is (= (apply concat (map :io.pedestal.app/emitter-deltas results))
               [[:node-create [] :map]
                [:node-create [:a] :map]
                [:node-create [:a :counter] :map]
                [:value [:a :counter] nil {:a 1}]
                [:value [:a :counter] {:a 1} {:a 1 :b 1}]
                [:value [:a :counter] {:a 1 :b 1} nil]
                [:node-destroy [:a :counter] :map]
                [:node-destroy [:a] :map]
                [:node-create [:b] :map]
                [:node-create [:b :counter] :map]
                [:value [:b :counter] nil {:c 1 :d 1}]
                [:value [:b :counter] {:c 1 :d 1} {:c 1}]
                [:value [:b :counter] {:c 1} {:c 2}]
                [:value [:b :counter] {:c 2} nil]
                [:node-destroy [:b :counter] :map]
                [:node-destroy [:b] :map]
                [:node-create [:a] :map]
                [:node-create [:a :counter] :map]
                [:value [:a :counter] nil {:a 1 :b 1}]])))
      (let [app (build (assoc dataflow :emit [[#{[:* :* :*]} (default-emitter [])]]))
            _ (begin app)
            results (run-sync! app messages)
            results (standardize-results results)]
        (is (= (partition-sets (apply concat (map :io.pedestal.app/emitter-deltas results))
                               [8 12 12])
               [#{[:node-create [] :map]
                  [:node-create [:a] :map]
                  [:node-create [:a :counter] :map]
                  [:node-create [:a :counter :a] :map]
                  [:value [:a :counter :a] nil 1]
                  [:node-create [:a :counter :b] :map]
                  [:value [:a :counter :b] nil 1]
                  [:value [:a :counter :b] 1 nil]}
                #{[:node-destroy [:a :counter :b] :map]
                  [:value [:a :counter :a] 1 nil]
                  [:node-destroy [:a :counter :a] :map]
                  [:node-destroy [:a :counter] :map]
                  [:node-destroy [:a] :map]
                  [:node-create [:b] :map]
                  [:node-create [:b :counter] :map]
                  [:node-create [:b :counter :c] :map]
                  [:value [:b :counter :c] nil 1]
                  [:node-create [:b :counter :d] :map]
                  [:value [:b :counter :d] nil 1]
                  [:value [:b :counter :d] 1 nil]}
                #{[:node-destroy [:b :counter :d] :map]
                  [:value [:b :counter :c] 1 2]
                  [:value [:b :counter :c] 2 nil]
                  [:node-destroy [:b :counter :c] :map]
                  [:node-destroy [:b :counter] :map]
                  [:node-destroy [:b] :map]
                  [:node-create [:a] :map]
                  [:node-create [:a :counter] :map]
                  [:node-create [:a :counter :a] :map]
                  [:value [:a :counter :a] nil 1]
                  [:node-create [:a :counter :b] :map]
                  [:value [:a :counter :b] nil 1]}]))))))

(deftest test-post-processing
  (let [inc-t (fn [old message] ((fnil inc 0) old))
        test-matcher (fn [p] (fn [x] (= (first x) p)))
        tag-out (fn [tag] (fn [m] [(cons tag m)]))
        value-as-currency (fn [[op path v]] [[op path (str "$" v)]])
        flow {:version 2
              :transform [[:inc [:*] inc-t]]
              :effect #{[#{[:a]} dataflow/input-map]
                        [#{[:b]} dataflow/input-map]}
              :post {:effect    [[(test-matcher [:a]) (tag-out :a-tag)]
                                 [(test-matcher [:b]) (tag-out :b-tag)]]
                     :app-model [[:value [:a] value-as-currency]]}}]
    (let [output-state (atom [])
          app-model-state (atom [])
          app (build flow)
          _ (capture-queue 6 :output app output-state)
          _ (capture-queue 6 :app-model app app-model-state)
          _ (run-sync! app [{::msg/topic [:a] msg/type :inc}
                            {::msg/topic msg/output :payload [[:z] 9999]}
                            {::msg/topic [:b] msg/type :inc}]
                       :begin :default)]
      (is (= @output-state
             [[:a-tag [:a] 1]
              [[:z] 9999]
              [:b-tag [:b] 1]]))
      (is (= (mapcat :deltas @app-model-state)
             [[:node-create [:a] :map]
              [:value [:a] "$1"]
              [:node-create [:b] :map]
              [:value [:b] 1]])))))

(letfn [(transform-fn [map message]
          (reduce (fn [m [k v]]
                    (-> m
                        (assoc-in [:baz k] v)
                        (assoc-in [:quux k] v)))
                  map
                  (:value message)))]
  (deftest test-multiple-emit-handlers
    (let [app (build {:version 2
                      :transform [[:test-transform [:bar] transform-fn]]
                      :emit [[#{[:bar :baz :*]} (default-emitter :foo)]
                             [#{[:bar :quux :*]} (default-emitter :foo)]
                             [#{[:*]} (default-emitter :foo)]]})
          app-model (render/consume-app-model app (constantly nil))]
      (is (run-sync! app [{msg/type :test-transform ::msg/topic [:bar] :value {:a 10 :b 20}}]
                     :begin :default
                     :wait-for [:app-model]))
      (is (= (-> app :state deref :data-model)
             {:bar {:quux {:a 10 :b 20}
                    :baz {:a 10 :b 20}}}))
      (is (= (set (q '[:find ?seg ?v
                       :where
                       [?child :t/value ?v]
                       [?child :t/segment ?seg]
                       [?child :t/parent ?e]
                       [?e :t/path [:foo :bar :quux]]]
                     @app-model))
             #{[:a 10] [:b 20]})))))

(deftest test-truthy-data-model-values
  (let [app (build {:version 2
                    :transform [[:set-value [:*] (fn [_ message] (:value message))]]})
        results (run-sync! app [{msg/type :set-value ::msg/topic [:x] :value true}
                                {msg/type :set-value ::msg/topic [:x] :value false}]
                           :begin :default)
        results (standardize-results results)]
    (is (= (-> app :state deref :data-model)
           {:x false}))
    (is (= (apply concat (map :io.pedestal.app/emitter-deltas results))
           [[:node-create [] :map]
            [:node-create [:x] :map]
            [:value [:x] nil true]
            [:value [:x] true false]])))
  (let [app (build {:version 2
                    :transform [[:set-value [:*] (fn [_ message] (:value message))]]})
        results (run-sync! app [{msg/type :set-value ::msg/topic [:x] :value true}
                                {msg/type :set-value ::msg/topic [:x] :value nil}]
                           :begin :default)
        results (standardize-results results)]
    (is (= (-> app :state deref :data-model)
           {:x nil}))
    (is (= (apply concat (map :io.pedestal.app/emitter-deltas results))
           [[:node-create [] :map]
            [:node-create [:x] :map]
            [:value [:x] nil true]
            [:value [:x] true nil]])))
  (let [app (build {:version 2
                    :transform [[:set-value [:*] (fn [_ message] (:value message))]]
                    :emit [[#{[:* :*]} (default-emitter [])]]})
        results (run-sync! app [{msg/type :set-value ::msg/topic [:x] :value {:a 1 :b 1}}
                                {msg/type :set-value ::msg/topic [:x] :value {:a 0 :b 1}}
                                {msg/type :set-value ::msg/topic [:x] :value {:a 0}}
                                {msg/type :set-value ::msg/topic [:x] :value {:a nil}}
                                {msg/type :set-value ::msg/topic [:x] :value nil}]
                           :begin :default)
        results (standardize-results results)]
    (is (= (-> app :state deref :data-model)
           {:x nil}))
    (is (= (set (apply concat (map :io.pedestal.app/emitter-deltas results)))
           #{[:node-create [] :map]
             [:node-create [:x] :map]
             [:node-create [:x :b] :map]
             [:value [:x :b] nil 1]
             [:node-create [:x :a] :map]
             [:value [:x :a] nil 1]
             [:value [:x :a] 1 0]
             [:value [:x :b] 1 nil]
             [:node-destroy [:x :b] :map]
             [:value [:x :a] 0 nil]
             [:node-destroy [:x :a] :map]})))
  (let [app (build {:version 2
                    :transform [[:set-value [:*] (fn [_ message] (:value message))]]
                    :emit [[#{[:* :*]} (default-emitter [])]]})
        results (run-sync! app [{msg/type :set-value ::msg/topic [:x] :value {:a true :b true}}
                                {msg/type :set-value ::msg/topic [:x] :value {:a false :b true}}
                                {msg/type :set-value ::msg/topic [:x] :value {:a false}}
                                {msg/type :set-value ::msg/topic [:x] :value {:a nil}}
                                {msg/type :set-value ::msg/topic [:x] :value nil}]
                           :begin :default)
        results (standardize-results results)]
    (is (= (-> app :state deref :data-model)
           {:x nil}))
    (is (= (set (apply concat (map :io.pedestal.app/emitter-deltas results)))
           #{[:node-create [] :map]
             [:node-create [:x] :map]
             [:node-create [:x :b] :map]
             [:value [:x :b] nil true]
             [:node-create [:x :a] :map]
             [:value [:x :a] nil true]
             [:value [:x :a] true false]
             [:value [:x :b] true nil]
             [:node-destroy [:x :b] :map]
             [:value [:x :a] false nil]
             [:node-destroy [:x :a] :map]}))))

(deftest test-configure-arguments
  (let [swap (fn [_ message] (:value message))
        double (fn [_ inputs] (* (dataflow/single-val inputs) 2))
        better-double (fn [_ n] (* n 2))
        sum (fn [_ inputs] (reduce + (dataflow/input-vals inputs)))
        better-sum (fn [_ nums] (reduce + nums))
        rotate (fn [_ inputs]
                 (let [{a [:x :a]
                        b [:x :b]
                        c [:x :c]} (dataflow/input-map inputs)]
                   {:a b :b c :c a}))
        better-rotate (fn [_ {:keys [a b c]}]
                        {:a b :b c :c a})
        group (fn [_ {:keys [x c]}]
                {:x x :c c})
        id (fn [_ x] x)]
    (let [app (build {:version 2
                      :transform [[:swap [:*] swap]]
                      :derive #{[#{[:x :a]} [:m :a] double]
                                [#{[:y :b]} [:m :b] better-double :single-val]
                                [#{[:x :*]} [:n :a] sum]
                                [#{[:y :*]} [:n :b] better-sum :vals]
                                [#{[:x :a] [:x :b] [:x :c]} [:o] rotate]
                                [{[:x :a] :a [:x :b] :b [:x :c] :c} [:p] better-rotate :map]
                                [{[:x :*] last} [:q] better-rotate :map]
                                [{[:x :a] :x [:x :b] :x [:x :c] :c} [:r] group :map]
                                [{[:x :*] :x [:y :c] :c} [:s] group :map]
                                [{[:y :a] :x [:y :b] :x [:x :*] :x [:y :c] :c} [:t] group :map]
                                [#{[:x :b]} [:u :a] id :single-val]
                                [{[:u :*] :x} [:v :a] id :map]
                                [{[:u :a] :x [:x :z] :x} [:v :b] id :map]}})
          results (run-sync! app [{msg/type :swap ::msg/topic [:x] :value {:a 1 :b 2 :c 3}}
                                  {msg/type :swap ::msg/topic [:y] :value {:a 5 :b 6 :c 7}}]
                             :begin :default)
          results (standardize-results results)]
      (is (= (-> app :state deref :data-model)
             {:x {:a 1 :b 2 :c 3}
              :y {:a 5 :b 6 :c 7}
              :o {:a 2 :b 3 :c 1}
              :p {:a 2 :b 3 :c 1}
              :q {:a 2 :b 3 :c 1}
              :r {:x #{1 2} :c 3}
              :s {:x #{1 2 3} :c 7}
              :t {:x #{1 2 3 5 6} :c 7}
              :m {:a 2 :b 12}
              :n {:a 6 :b 18}
              :u {:a 2}
              :v {:a {:x #{2}} :b {:x #{2}}}})))))

