;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.test.app
  (:require [io.pedestal.app.protocols :as p]
            [io.pedestal.app.messages :as msg]
            [io.pedestal.app.tree :as tree])
  (:use io.pedestal.app
        clojure.test))

(deftest test-views-for-input
  (let [views-for-input #'io.pedestal.app/views-for-input]
    (is (= (views-for-input {:view-word    {:input #{:model-a} :fn :word-fn}
                             :view-reverse {:input #{:model-a :view-word} :fn :reverse-fn}
                             :view-count   {:input #{:model-v} :fn :count-fn}}
                            :model-a)
           #{:view-word :view-reverse}))
    (is (= (views-for-input {:view-word    {:input #{:model-a} :fn :word-fn}
                             :view-reverse {:input #{:model-a :view-word} :fn :reverse-fn}
                             :view-count   {:input #{:model-v} :fn :count-fn}}
                            :view-word)
           #{:view-reverse}))
    (is (= (views-for-input {:view-word    {:input #{:model-a} :fn :word-fn}
                             :view-reverse {:input #{:model-a :view-word} :fn :reverse-fn}
                             :view-count   {:input #{:model-v} :fn :count-fn}}
                            :model-x)
           #{:io.pedestal.app/view-model-x}))))

(deftest test-emitters-for-input
  (let [emitters-for-input #'io.pedestal.app/emitters-for-input]
    (is (= (emitters-for-input {:emitter-test {:input #{:view-word :view-reverse} :fn :emitter-fn}}
                               {:k :view-word :type :view})
           #{:emitter-test}))
    (is (= (emitters-for-input {:emitter-test {:input #{:view-word :view-reverse} :fn :emitter-fn}}
                               {:k :io.pedestal.app/view-model-a :type :view})
           #{}))
    (is (= (emitters-for-input {}
                               {:k :io.pedestal.app/view-model-a :type :view})
           #{:io.pedestal.app/default-emitter}))
    (is (= (emitters-for-input {}
                               {:k :model-a :type :model})
           #{}))))

(def two-model-system-flow-defaults
  {:default-emitter :io.pedestal.app/default-emitter
   :models {:model-a :model-a-fn
            :model-b :model-b-fn}
   :model->output {:model-a default-output-fn
                   :model-b default-output-fn}
   :input->views {:model-a #{:io.pedestal.app/view-model-a}
                  :model-b #{:io.pedestal.app/view-model-b}}
   :view->events {:io.pedestal.app/view-model-a default-events-fn
                  :io.pedestal.app/view-model-b default-events-fn}
   :input->emitters {:io.pedestal.app/view-model-a #{:io.pedestal.app/default-emitter}
                     :io.pedestal.app/view-model-b #{:io.pedestal.app/default-emitter}}
   :views {:io.pedestal.app/view-model-a {:fn default-view-fn :input #{:model-a}}
           :io.pedestal.app/view-model-b {:fn default-view-fn :input #{:model-b}}}
   :emitters {:io.pedestal.app/default-emitter {:fn default-emitter-fn
                                             :input #{:io.pedestal.app/view-model-a
                                                      :io.pedestal.app/view-model-b}}}})

(deftest test-make-flow
  (testing "one model - and nothing else"
    (is (= (make-flow {:models {:model-a {:init "" :fn :function-a}}})
           {:default-emitter :io.pedestal.app/default-emitter
            :models {:model-a :function-a}
            :model->output {:model-a default-output-fn}
            :input->views {:model-a #{:io.pedestal.app/view-model-a}}
            :view->events {:io.pedestal.app/view-model-a default-events-fn}
            :input->emitters {:io.pedestal.app/view-model-a #{:io.pedestal.app/default-emitter}}
            :views {:io.pedestal.app/view-model-a {:fn default-view-fn :input #{:model-a}}}
            :emitters {:io.pedestal.app/default-emitter
                       {:fn default-emitter-fn :input #{:io.pedestal.app/view-model-a}}}})))
  (testing "two models - and nothing else"
    (is (= (make-flow {:models {:model-a {:init "" :fn :model-a-fn}
                                :model-b {:init "" :fn :model-b-fn}}})
           two-model-system-flow-defaults)))
  (testing "provide output fn"
    (is (= (make-flow {:models {:model-a {:init "" :fn :model-a-fn}
                                :model-b {:init "" :fn :model-b-fn}}
                       :output {:model-a :output-fn-a}})
           (merge two-model-system-flow-defaults
                  {:model->output {:model-a :output-fn-a
                                   :model-b default-output-fn}}))))
  (testing "provide one view fn"
    (is (= (make-flow {:models {:model-a {:init "" :fn :model-a-fn}
                                :model-b {:init "" :fn :model-b-fn}}
                       :views {:view-a {:fn :view-a-fn :input #{:model-a}}}})
           (merge
            two-model-system-flow-defaults
            {:input->views {:model-a #{:view-a}
                            :model-b #{:io.pedestal.app/view-model-b}}
             :view->events {:view-a default-events-fn
                            :io.pedestal.app/view-model-b default-events-fn}
             :input->emitters {:view-a #{:io.pedestal.app/default-emitter}
                               :io.pedestal.app/view-model-b #{:io.pedestal.app/default-emitter}}
             :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                     :io.pedestal.app/view-model-b {:fn default-view-fn :input #{:model-b}}}
             :emitters {:io.pedestal.app/default-emitter
                        {:fn default-emitter-fn
                         :input #{:view-a :io.pedestal.app/view-model-b}}}}))))
  (testing "provide all view fns"
    (is (= (make-flow {:models {:model-a {:init "" :fn :model-a-fn}
                                :model-b {:init "" :fn :model-b-fn}}
                       :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                               :view-b {:fn :view-b-fn :input #{:model-b}}}})
           (merge
            two-model-system-flow-defaults
            {:input->views {:model-a #{:view-a}
                            :model-b #{:view-b}}
             :view->events {:view-a default-events-fn
                            :view-b default-events-fn}
             :input->emitters {:view-a #{:io.pedestal.app/default-emitter}
                               :view-b #{:io.pedestal.app/default-emitter}}
             :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                     :view-b {:fn :view-b-fn :input #{:model-b}}}
             :emitters {:io.pedestal.app/default-emitter
                        {:fn default-emitter-fn
                         :input #{:view-a :view-b}}}}))))
  (testing "provide three view fns for two models"
    (is (= (make-flow {:models {:model-a {:init "" :fn :model-a-fn}
                                :model-b {:init "" :fn :model-b-fn}}
                       :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                               :view-b {:fn :view-b-fn :input #{:model-b}}
                               :view-c {:fn :view-c-fn :input #{:model-a :model-b}}}})
           (merge
            two-model-system-flow-defaults
            {:input->views {:model-a #{:view-a :view-c}
                            :model-b #{:view-b :view-c}}
             :view->events {:view-a default-events-fn
                            :view-b default-events-fn
                            :view-c default-events-fn}
             :input->emitters {:view-a #{:io.pedestal.app/default-emitter}
                               :view-b #{:io.pedestal.app/default-emitter}
                               :view-c #{:io.pedestal.app/default-emitter}}
             :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                     :view-b {:fn :view-b-fn :input #{:model-b}}
                     :view-c {:fn :view-c-fn :input #{:model-b :model-a}}}
             :emitters {:io.pedestal.app/default-emitter
                        {:fn default-emitter-fn
                         :input #{:view-a :view-b :view-c}}}}))))
  (testing "provide a view which takes a another view as input"
    (is (= (make-flow {:models {:model-a {:init "" :fn :model-a-fn}
                                :model-b {:init "" :fn :model-b-fn}}
                       :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                               :view-b {:fn :view-b-fn :input #{:model-b}}
                               :view-c {:fn :view-c-fn :input #{:model-a :model-b}}
                               :view-d {:fn :view-d-fn :input #{:model-a :view-b}}}})
           (merge
            two-model-system-flow-defaults
            {:input->views {:model-a #{:view-a :view-c :view-d}
                            :model-b #{:view-b :view-c}
                            :view-b #{:view-d}}
             :view->events {:view-a default-events-fn
                            :view-b default-events-fn
                            :view-c default-events-fn
                            :view-d default-events-fn}
             :input->emitters {:view-a #{:io.pedestal.app/default-emitter}
                               :view-b #{:io.pedestal.app/default-emitter}
                               :view-c #{:io.pedestal.app/default-emitter}
                               :view-d #{:io.pedestal.app/default-emitter}}
             :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                     :view-b {:fn :view-b-fn :input #{:model-b}}
                     :view-c {:fn :view-c-fn :input #{:model-b :model-a}}
                     :view-d {:fn :view-d-fn :input #{:model-a :view-b}}}
             :emitters {:io.pedestal.app/default-emitter
                        {:fn default-emitter-fn
                         :input #{:view-a :view-b :view-c :view-d}}}}))))
  (testing "provide events"
    (is (= (make-flow {:models {:model-a {:init "" :fn :model-a-fn}
                                :model-b {:init "" :fn :model-b-fn}}
                       :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                               :view-b {:fn :view-b-fn :input #{:model-b}}}
                       :events {:view-a :events-a-fn}})
           (merge
            two-model-system-flow-defaults
            {:input->views {:model-a #{:view-a}
                            :model-b #{:view-b}}
             :view->events {:view-a :events-a-fn
                            :view-b default-events-fn}
             :input->emitters {:view-a #{:io.pedestal.app/default-emitter}
                               :view-b #{:io.pedestal.app/default-emitter}}
             :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                     :view-b {:fn :view-b-fn :input #{:model-b}}}
             :emitters {:io.pedestal.app/default-emitter
                        {:fn default-emitter-fn
                         :input #{:view-a :view-b}}}}))))
  (testing "provide one emitter"
    (is (= (make-flow {:models {:model-a {:init "" :fn :model-a-fn}
                                :model-b {:init "" :fn :model-b-fn}}
                       :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                               :view-b {:fn :view-b-fn :input #{:model-b}}}
                       :emitters {:emitter-a {:fn :emitter-a-fn :input #{:view-a :view-b}}}})
           (merge
            two-model-system-flow-defaults
            {:default-emitter :emitter-a
             :input->views {:model-a #{:view-a}
                            :model-b #{:view-b}}
             :view->events {:view-a default-events-fn
                            :view-b default-events-fn}
             :input->emitters {:view-a #{:emitter-a}
                               :view-b #{:emitter-a}}
             :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                     :view-b {:fn :view-b-fn :input #{:model-b}}}
             :emitters {:emitter-a {:fn :emitter-a-fn :input #{:view-a :view-b}}}}))))
  (testing "provide two emitters"
    (is (= (make-flow {:default-emitter :emitter-a
                       :models {:model-a {:init "" :fn :model-a-fn}
                                :model-b {:init "" :fn :model-b-fn}}
                       :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                               :view-b {:fn :view-b-fn :input #{:model-b}}}
                       :emitters {:emitter-a {:fn :emitter-a-fn :input #{:view-a}}
                                  :emitter-b {:fn :emitter-b-fn :input #{:view-b}}}})
           (merge
            two-model-system-flow-defaults
            {:default-emitter :emitter-a
             :input->views {:model-a #{:view-a}
                            :model-b #{:view-b}}
             :view->events {:view-a default-events-fn
                            :view-b default-events-fn}
             :input->emitters {:view-a #{:emitter-a}
                               :view-b #{:emitter-b}}
             :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                     :view-b {:fn :view-b-fn :input #{:model-b}}}
             :emitters {:emitter-a {:fn :emitter-a-fn :input #{:view-a}}
                        :emitter-b {:fn :emitter-b-fn :input #{:view-b}}}}))))
  (testing "provide everything"
    (is (= (make-flow {:default-emitter :emitter-a
                       :models {:model-a {:init "" :fn :model-a-fn}
                                :model-b {:init "" :fn :model-b-fn}}
                       :output {:model-a :output-a-fn
                                :model-b :output-b-fn}
                       :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                               :view-b {:fn :view-b-fn :input #{:model-b}}}
                       :events {:view-a :events-a-fn
                                :view-b :events-b-fn}
                       :emitters {:emitter-a {:fn :emitter-a-fn :input #{:view-a}}
                                  :emitter-b {:fn :emitter-b-fn :input #{:view-b}}}})
           (merge
            two-model-system-flow-defaults
            {:default-emitter :emitter-a
             :input->views {:model-a #{:view-a}
                            :model-b #{:view-b}}
             :model->output {:model-a :output-a-fn
                             :model-b :output-b-fn}
             :view->events {:view-a :events-a-fn
                            :view-b :events-b-fn}
             :input->emitters {:view-a #{:emitter-a}
                               :view-b #{:emitter-b}}
             :views {:view-a {:fn :view-a-fn :input #{:model-a}}
                     :view-b {:fn :view-b-fn :input #{:model-b}}}
             :emitters {:emitter-a {:fn :emitter-a-fn :input #{:view-a}}
                        :emitter-b {:fn :emitter-b-fn :input #{:view-b}}}}))))
  (testing "views which are not included on the given emitter"
    (is (= (make-flow {:models {:model-guess    {:init 0 :fn :model-guess-fn}
                                :model-x        {:init 0 :fn :model-x-fn}}
                       :views  {:view-divide    {:fn :view-divide-fn :input #{:model-x :model-guess}}
                                :view-x         {:fn :view-x-fn :input #{:model-x}}
                                :view-sum       {:fn :view-sum-fn :input #{:model-guess :view-divide}}}
                       :emitters {:emitter-answer {:fn :emitter-answer-fn :input #{:view-x :view-sum}}}})
           {:default-emitter :emitter-answer
            :models {:model-guess :model-guess-fn
                     :model-x :model-x-fn}
            :input->views {:model-guess #{:view-divide :view-sum}
                           :model-x #{:view-x :view-divide}
                           :view-divide #{:view-sum}}
            :model->output {:model-guess default-output-fn
                            :model-x default-output-fn}
            :view->events {:view-divide default-events-fn
                           :view-x default-events-fn
                           :view-sum default-events-fn}
            :input->emitters {:view-x #{:emitter-answer}
                              :view-sum #{:emitter-answer}}
            :views {:view-divide {:fn :view-divide-fn :input #{:model-x :model-guess}}
                    :view-x {:fn :view-x-fn :input #{:model-x}}
                    :view-sum {:fn :view-sum-fn :input #{:model-guess :view-divide}}}
            :emitters {:emitter-answer {:fn :emitter-answer-fn :input #{:view-x :view-sum}}}})))
  (testing "model can be used as a view"
    (is (= (make-flow {:models {:model-guess    {:init 0 :fn :model-guess-fn}
                                :model-x        {:init 0 :fn :model-x-fn}}
                       :views  {:view-divide    {:fn :view-divide-fn :input #{:model-x :model-guess}}
                                :view-sum       {:fn :view-sum-fn :input #{:model-guess :view-divide}}}
                       :emitters {:emitter-answer {:fn :emitter-answer-fn :input #{:model-x :view-sum}}}})
           {:default-emitter :emitter-answer
            :models {:model-guess :model-guess-fn
                     :model-x :model-x-fn}
            :input->views {:model-guess #{:view-divide :view-sum}
                           :model-x #{:view-divide}
                           :view-divide #{:view-sum}}
            :model->output {:model-guess default-output-fn
                            :model-x default-output-fn}
            :view->events {:view-divide default-events-fn
                           :view-sum default-events-fn}
            :input->emitters {:model-x #{:emitter-answer}
                              :view-sum #{:emitter-answer}}
            :views {:view-divide {:fn :view-divide-fn :input #{:model-x :model-guess}}
                    :view-sum {:fn :view-sum-fn :input #{:model-guess :view-divide}}}
            :emitters {:emitter-answer {:fn :emitter-answer-fn :input #{:model-x :view-sum}}}}))))

(deftest build-system
  (let [desc {:models {:model-a {:init "" :fn (fn [old event] (:value event))}}}
        app (build desc)]
    (is (= (dissoc @(:state app) :ui)
           {:events [] :output []}))))


;; Support functions
;; ================================================================================

(defn run-script
  "Send each message in the script to the applcation and return a
  sequence of all generated states."
  [app script]
  (reduce (fn [a message]
            (p/put-message (:input app) message)
            ;; TODO: Fix this
            (Thread/sleep 20)
            (conj a @(:state app)))
          [@(:state app)]
          script))

(defn input->emitter-output
  "Given a sequence of states return a sequence of maps with :input
  and :emitter."
  [states]
  (mapv (fn [x] {:input (:input x) :emitter (set (:emitter-deltas x))}) states))

(defn input->events
  "Given a sequence of states return a sequence of maps with :input
  and :emitter."
  [states]
  (mapv (fn [x] {:input (:input x) :events (:events x)}) states))

(deftest test-topo-sort
  (let [flow (make-flow {:models {:model-guess    {:init 0 :fn :fn-a}
                                  :model-x        {:init 0 :fn :fn-b}}
                         :views  {:view-divide    {:fn :fn-c
                                                   :input #{:model-x :model-guess}}
                                  :view-x         {:fn :fn-e :input #{:model-x}}
                                  :view-sum       {:fn :fn-f :input #{:model-guess :view-divide}}
                                  :view-half      {:fn :fn-g :input #{:view-sum}}
                                  :view-test      {:fn :x :input #{:model-guess}}}})]
    (is (= (remove #{:view-test :view-x}
                   (topo-sort flow #{:view-half :view-sum :view-divide :view-test :view-x}))
           [:view-divide :view-sum :view-half]))))


;; Simplest possible application
;; ================================================================================

(defn number-model [old event]
  (case (msg/type event)
    :io.pedestal.app.messages/init (:value event)
    (:n event)))

(def simplest-possible-app
  {:models {:model-a {:init 0 :fn number-model}}})

(defn standardize-results [results]
  (let [tree (atom tree/new-app-model)]
    (mapv (fn [r]
            (let [d (:emitter-deltas r)
                  old-tree @tree
                  new-tree (swap! tree tree/apply-deltas d)]
              (assoc r :emitter-deltas (tree/since-t new-tree (tree/t old-tree)))))
          results)))

(deftest test-simplest-possible-app
  (let [app (build simplest-possible-app)
        _ (begin app)
        results (run-script app [{msg/topic :model-a :n 42}])
        results (standardize-results results)]
    (is (= (count results) 2))
    (is (= (first results)
           {:input {msg/topic msg/app-model
                    msg/type :subscribe
                    :paths [[]]}
            :subscriptions [[]]
            :models {:model-a 0}
            :views {:io.pedestal.app/view-model-a 0}
            :events []
            :output []
            :emitter-deltas [[:node-create [] :map]
                             [:node-create [:io.pedestal.app/view-model-a] :map]
                             [:value [:io.pedestal.app/view-model-a] nil 0]]}))
    (is (= (last results)
           {:input {msg/topic :model-a :n 42}
            :models {:model-a 42}
            :subscriptions [[]]
            :views {:io.pedestal.app/view-model-a 42}
            :events []
            :output []
            :emitter-deltas [[:value [:io.pedestal.app/view-model-a] 0 42]]}))
    (is (= (input->emitter-output results)
           [{:input {msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:io.pedestal.app/view-model-a] :map]
                        [:value [:io.pedestal.app/view-model-a] nil 0]}}
            {:input {msg/topic :model-a :n 42}
             :emitter #{[:value [:io.pedestal.app/view-model-a] 0 42]}}]))))


;; Two models + defaults
;; ================================================================================

(def two-models-app
  {:models {:model-a {:init 0 :fn number-model}
            :model-b {:init 0 :fn number-model}}})

(deftest test-two-models-app
  (let [app (build two-models-app)
        _ (begin app)
        results (run-script app [{msg/topic :model-a :n 42}
                                 {msg/topic :model-b :n 11}
                                 {msg/topic :model-a :n 3}])
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input {msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:io.pedestal.app/view-model-a] :map]
                        [:value [:io.pedestal.app/view-model-a] nil 0]
                        [:node-create [:io.pedestal.app/view-model-b] :map]
                        [:value [:io.pedestal.app/view-model-b] nil 0]}}
            {:input {msg/topic :model-a :n 42}
             :emitter #{[:value [:io.pedestal.app/view-model-a] 0 42]}}
            {:input {msg/topic :model-b :n 11}
             :emitter #{[:value [:io.pedestal.app/view-model-b] 0 11]}}
            {:input {msg/topic :model-a :n 3}
             :emitter #{[:value [:io.pedestal.app/view-model-a] 42 3]}}]))))


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
   :model-b {:init 0 :fn number-model}
   :views   {:view-sum  {:fn sum  :input #{:model-a :model-b}}
             :view-half {:fn half :input #{:model-b}}}})

(deftest test-two-views-app
  (let [app (build two-views-app)
        _ (begin app)
        results (run-script app [{msg/topic :model-a :n 42}
                                 {msg/topic :model-b :n 10}
                                 {msg/topic :model-a :n 3}])
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input {msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:view-sum] :map]
                        [:value [:view-sum] nil 0]
                        [:node-create [:view-half] :map]
                        [:value [:view-half] nil 0.0]}}
            {:input {msg/topic :model-a :n 42}
             :emitter #{[:value [:view-sum] 0 42]}}
            {:input {msg/topic :model-b :n 10}
             :emitter #{[:value [:view-half] 0.0 5.0]
                        [:value [:view-sum] 42 52]}}
            {:input {msg/topic :model-a :n 3}
             :emitter #{[:value [:view-sum] 52 13]}}]))))

;; In the next few tests, we will use the application framework to
;; calculate the square root of a number. We will encode Heron's
;; method of iteratively approximating the square root until the
;; disired accuracy is achieved.

;; This will test several advanced features of the framework,
;; including:
;;
;; - Views which have other views as input
;; - Views which generate events
;; - Recursive event processing within a single transaction

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

(defn continue-calc [view-name o n]
  (when (not (or (:good-enough? n)
                 (= (:new-guess n) :NaN)))
    [{msg/topic :guess :n (:new-guess n)}]))

;; Calculate square root using Heron's method
;; ================================================================================
;; this will be the first time that test recursion based on an events function

(def square-root-app
  {:models  {:guess    {:init 0 :fn number-model}
             :x        {:init 0 :fn number-model}
             :accuracy {:init 0 :fn number-model}}
   :views   {:divide       {:fn (divider :x :guess) :input #{:x :guess}}
             :sum          {:fn sum :input #{:guess :divide}}
             :half         {:fn half :input #{:sum}}
             :good-enough? {:fn good-enough? :input #{:half :accuracy}}}
   :events  {:good-enough? continue-calc}
   :emitters {:answer {:fn default-emitter-fn :input #{:x :half}}}})

(deftest test-square-root
  (let [app (build square-root-app)
        _ (begin app)
        results (run-script app [{msg/topic :accuracy :n 0.000001}
                                 {msg/topic :x :n 42}
                                 {msg/topic :guess :n 7}])
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input {msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:x] :map]
                        [:value [:x] nil 0]
                        [:node-create [:half] :map]
                        [:value [:half] nil :NaN]}}
            {:input {msg/topic :accuracy :n 0.000001} :emitter #{}}
            {:input {msg/topic :x :n 42} :emitter #{[:value [:x] 0 42]}}
            {:input {:n 7 msg/topic :guess}
             :emitter #{[:value [:half] :NaN 6.5]
                        [:value [:half] 6.5 6.480769230769231]
                        [:value [:half] 6.480769230769231 6.480740698470669]
                        [:value [:half] 6.480740698470669 6.48074069840786]}}]))))

(deftest test-square-root-modify-inputs
  (let [app (build square-root-app)
        _ (begin app)
        results (run-script app [{msg/topic :accuracy :n 0.000001}
                                 {msg/topic :x :n 42}
                                 {msg/topic :guess :n 7}
                                 {msg/topic :x :n 50}])
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input {msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                       [:node-create [:x] :map]
                       [:value [:x] nil 0]
                       [:node-create [:half] :map]
                       [:value [:half] nil :NaN]}}
            {:input {msg/topic :accuracy :n 0.000001} :emitter #{}}
            {:input {msg/topic :x :n 42} :emitter #{[:value [:x] 0 42]}}
            {:input {:n 7 msg/topic :guess}
             :emitter #{[:value [:half] :NaN 6.5]
                       [:value [:half] 6.5 6.480769230769231]
                       [:value [:half] 6.480769230769231 6.480740698470669]
                       [:value [:half] 6.480740698470669 6.48074069840786]}}
            {:input {msg/topic :x :n 50}
             :emitter
             #{[:value [:half] 6.48074069840786 7.097954098250247]
               [:value [:half] 7.097954098250247 7.071118733045407]
               [:value [:half] 7.071118733045407 7.071067812048824]
               [:value [:half] 7.071067812048824 7.0710678118654755]
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
        _ (begin app)
        results (run-script app [{msg/topic :x :n 42}
                                 {msg/topic :x :n 12}])
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input {msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:x] :map]
                        [:value [:x] nil 0]
                        [:node-create [:sum] :map]
                        [:value [:sum] nil 0.0]}}
            {:input {msg/topic :x :n 42}
             :emitter #{[:value [:x] 0 42]
                        [:value [:sum] 0.0 1827.0]}}
            {:input {msg/topic :x :n 12}
             :emitter #{[:value [:x] 42 12]
                        [:value [:sum] 1827.0 162.0]}}]))))

(def two-views-with-same-input-old-values
  {:models  {:x {:init 0 :fn number-model}}
   :views   {:a {:fn (fn [_ _ o _] o) :input #{:x}}
             :b {:fn (fn [_ _ o _] o) :input #{:x}}}
   :emitters {:answer {:fn default-emitter-fn :input #{:a :b}}}})

(deftest test-two-views-with-same-input-old-values
  (let [app (build two-views-with-same-input-old-values)
        _ (begin app)
        results (run-script app [{msg/topic :x :n 1}
                                 {msg/topic :x :n 2}])
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input {msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:a] :map]
                        [:node-create [:b] :map]}}
            {:input {msg/topic :x :n 1}
             :emitter #{[:value [:a] nil 0]
                        [:value [:b] nil 0]}}
            {:input {msg/topic :x :n 2}
             :emitter #{[:value [:a] 0 1]
                        [:value [:b] 0 1]}}]))))

(def two-views-with-same-input-new-values
  {:models  {:x {:init 0 :fn number-model}}
   :views   {:a {:fn (fn [_ _ _ n] n) :input #{:x}}
             :b {:fn (fn [_ _ _ n] n) :input #{:x}}}
   :emitters {:answer {:fn default-emitter-fn :input #{:a :b}}}})

(deftest test-two-views-with-same-input-new-values
  (let [app (build two-views-with-same-input-new-values)
        _ (begin app)
        results (run-script app [{msg/topic :x :n 1}
                                 {msg/topic :x :n 2}])
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input {msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:a] :map]
                        [:value [:a] nil 0]
                        [:node-create [:b] :map]
                        [:value [:b] nil 0]}}
            {:input {msg/topic :x :n 1}
             :emitter #{[:value [:a] 0 1]
                        [:value [:b] 0 1]}}
            {:input {msg/topic :x :n 2}
             :emitter #{[:value [:a] 1 2]
                        [:value [:b] 1 2]}}]))))

;; Model output
;; ================================================================================

(defn echo-output [service-name]
  (fn [event old-model new-model]
    {:output [{msg/topic {:service service-name} :n new-model}]
     :events [{msg/topic :y :n (str new-model)}]}))

(defn capture-queue [n queue-name app state]
  (when (pos? n)
    (p/take-message (queue-name app)
                    (fn [message]
                      (swap! state conj message)
                      (capture-queue (dec n) queue-name app state)))))

(deftest test-output-app
  (let [services-state (atom [])
        output-app {:models   {:x      {:init 0 :fn number-model}
                               :y      {:init 0 :fn number-model}}
                    :views    {:half   {:fn half :input #{:x}}
                               :square {:fn square :input #{:x}}
                               :sum    {:fn sum :input #{:half :x :square}}}
                    :output   {:x (echo-output :s)}
                    :emitters  {:answer {:fn default-emitter-fn :input #{:x :y :sum}}}}
        app (build output-app)
        _ (capture-queue 3 :output app services-state)
        _ (begin app)
        results (run-script app [{msg/topic :x :n 42}
                                 {msg/topic :x :n 12}])
        results (standardize-results results)]
    (is (= @services-state
           [{msg/topic {:service :s} :n 0}
            {msg/topic {:service :s} :n 42}
            {msg/topic {:service :s} :n 12}]))
    (is (= (input->emitter-output results)
           [{:input {msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:x] :map]
                        [:value [:x] nil 0]
                        [:node-create [:y] :map]
                        [:value [:y] nil 0]
                        [:node-create [:sum] :map]
                        [:value [:sum] nil 0.0]}}
            {:input {msg/topic :x :n 42}
             :emitter #{[:value [:x] 0 42]
                        [:value [:y] 0 "42"]
                        [:value [:sum] 0.0 1827.0]}}
            {:input {msg/topic :x :n 12}
             :emitter #{[:value [:x] 42 12]
                        [:value [:y] "42" "12"]
                        [:value [:sum] 1827.0 162.0]}}]))))


;; Test with Renderer
;; ================================================================================

(deftest test-with-renderer
  (let [app (build dependent-views-app)
        _ (begin app)
        renderer-state (atom [])]
    (capture-queue 3 :app-model app renderer-state)
    (let [results (run-script app [{msg/topic :x :n 42}
                                   {msg/topic :x :n 12}])
          results (standardize-results results)]
      (is (= (set @renderer-state)
             #{#_{msg/topic msg/app-model
                msg/type :deltas
                :deltas [[:value [:x] 0]
                         [:value [:sum] 0.0]]}
               {msg/topic msg/app-model
                msg/type :deltas
                :deltas [[:node-create [:sum] :map]
                         [:value [:sum] nil 0.0]
                         [:node-create [:x] :map]
                         [:value [:x] nil 0]]}
               {msg/topic msg/app-model
                msg/type :deltas
                :deltas [[:value [:x] 42]
                         [:value [:sum] 1827.0]]}
               {msg/topic msg/app-model
                msg/type :deltas
                :deltas [[:value [:x] 12]
                         [:value [:sum] 162.0]]}}))
      (is (= (input->emitter-output results)
             [{:input {msg/topic msg/app-model
                       msg/type :subscribe
                       :paths [[]]}
               :emitter #{[:node-create [] :map]
                          [:node-create [:x] :map]
                          [:value [:x] nil 0]
                          [:node-create [:sum] :map]
                          [:value [:sum] nil 0.0]}}
              {:input {msg/topic :x :n 42}
               :emitter #{[:value [:x] 0 42]
                          [:value [:sum] 0.0 1827.0]}}
              {:input {msg/topic :x :n 12}
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
        _ (begin app)
        results (run-script app [{msg/topic :x :n 1}
                                 {msg/topic :x :n 2}])
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input {msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:e] :map]
                        [:value [:e] nil 0]}}
            {:input {msg/topic :x :n 1}
             :emitter #{[:value [:e] 0 3]}}
            {:input {msg/topic :x :n 2}
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
        _ (begin app)
        results (run-script app [{msg/topic :x :n 1}
                                 {msg/topic :x :n 2}])
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input {msg/topic msg/app-model
                     msg/type :subscribe
                     :paths [[]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:k] :map]
                        [:value [:k] nil 0]}}
            {:input {msg/topic :x :n 1}
             :emitter #{[:value [:k] 0 16]}}
            {:input {msg/topic :x :n 2}
             :emitter #{[:value [:k] 16 32]}}]))))

;; New App Model Navigation
;; ================================================================================

(deftest test-filter-deltas
  (is (= (filter-deltas {:subscriptions [[:a :b] [:a :j]]}
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

(deftest test-navigation-app
  (testing "only view the default paths"
    (let [app (build navigation-app)
          _ (begin app)
          results (run-script app [{msg/topic :a :n 10}
                                   {msg/topic :b :n 11}
                                   {msg/topic :c :n 12}])
          results (standardize-results results)]
      (is (= (input->emitter-output results)
             [{:input {msg/topic msg/app-model
                       msg/type :navigate
                       :name :a}
               :emitter #{[:node-create [] :map]
                          [:node-create [:a] :map]
                          [:value [:a] nil 1]}}
              {:input {msg/topic :a :n 10}
               :emitter #{[:value [:a] 1 10]}}
              {:input {msg/topic :b :n 11}
               :emitter #{}}
              {:input {msg/topic :c :n 12}
               :emitter #{}}]))))
  (testing "navigate between paths"
    (let [app (build navigation-app)
          _ (begin app)
          results (run-script app [{msg/topic :a :n 10}
                                   {msg/topic :b :n 11}
                                   {msg/topic msg/app-model msg/type :navigate :name :b}
                                   {msg/topic :b :n 12}
                                   {msg/topic :c :n 13}
                                   {msg/topic msg/app-model msg/type :navigate :name :c}
                                   {msg/topic :c :n 14}
                                   {msg/topic :a :n 15}
                                   {msg/topic msg/app-model msg/type :navigate :name :a}])
          results (standardize-results results)]
      (is (= (input->emitter-output results)
             [{:input {msg/topic msg/app-model msg/type :navigate :name :a}
               :emitter #{[:node-create [] :map]
                          [:node-create [:a] :map]
                          [:value [:a] nil 1]}}
              {:input {msg/topic :a :n 10} :emitter #{[:value [:a] 1 10]}}
              {:input {msg/topic :b :n 11} :emitter #{}}
              {:input {msg/topic msg/app-model msg/type :navigate :name :b}
               :emitter #{[:value [:a] 10 nil]
                          [:node-destroy [:a] :map]
                          [:node-create [:b] :map]
                          [:value [:b] nil 11]}}
              {:input {msg/topic :b :n 12} :emitter #{[:value [:b] 11 12]}}
              {:input {msg/topic :c :n 13} :emitter #{}}
              {:input {msg/topic msg/app-model msg/type :navigate :name :c}
               :emitter #{[:value [:b] 12 nil]
                          [:node-destroy [:b] :map]
                          [:node-create [:c] :map]
                          [:value [:c] nil 13]}}
              {:input {msg/topic :c :n 14} :emitter #{[:value [:c] 13 14]}}
              {:input {msg/topic :a :n 15} :emitter #{}}
              {:input {msg/topic msg/app-model msg/type :navigate :name :a}
               :emitter #{[:value [:c] 14 nil]
                          [:node-destroy [:c] :map]
                          [:node-create [:a] :map]
                          [:value [:a] nil 15]}}])))))

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
        _ (begin app [{msg/topic msg/app-model msg/type :noop}])
        results (run-script app [{msg/topic :a :n 10}
                                 {msg/topic msg/app-model msg/type :subscribe :paths [[:a]]}
                                 {msg/topic :b :n 11}
                                 {msg/topic :c :n 12}
                                 {msg/topic :a :n 13}
                                 {msg/topic msg/app-model msg/type :unsubscribe :paths [[:a]]}
                                 {msg/topic :c :n 14}
                                 {msg/topic msg/app-model msg/type :subscribe :paths [[:b] [:c]]}
                                 {msg/topic :a :n 15}
                                 {msg/topic :b :n 16}
                                 {msg/topic :c :n 17}
                                 {msg/topic msg/app-model msg/type :unsubscribe :paths [[:b]]}
                                 {msg/topic :a :n 18}
                                 {msg/topic :b :n 19}
                                 {msg/topic :c :n 20}])
        results (standardize-results results)]
    (is (= (input->emitter-output results)
           [{:input {msg/topic msg/app-model msg/type :noop}
             :emitter #{}}
            {:input {msg/topic :a :n 10} :emitter #{}}
            {:input {msg/topic msg/app-model msg/type :subscribe :paths [[:a]]}
             :emitter #{[:node-create [] :map]
                        [:node-create [:a] :map]
                        [:value [:a] nil 10]}}
            {:input {msg/topic :b :n 11} :emitter #{}}
            {:input {msg/topic :c :n 12} :emitter #{}}
            {:input {msg/topic :a :n 13} :emitter #{[:value [:a] 10 13]}}
            {:input {msg/topic msg/app-model msg/type :unsubscribe :paths [[:a]]}
             :emitter #{[:value [:a] 13 nil]
                        [:node-destroy [:a] :map]}}
            {:input {msg/topic :c :n 14} :emitter #{}}
            {:input {msg/topic msg/app-model msg/type :subscribe :paths [[:b] [:c]]}
             :emitter #{[:node-create [:b] :map]
                        [:value [:b] nil 11]
                        [:node-create [:c] :map]
                        [:value [:c] nil 14]}}
            {:input {msg/topic :a :n 15} :emitter #{}}
            {:input {msg/topic :b :n 16} :emitter #{[:value [:b] 11 16]}}
            {:input {msg/topic :c :n 17} :emitter #{[:value [:c] 14 17]}}
            {:input {msg/topic msg/app-model msg/type :unsubscribe :paths [[:b]]}
             :emitter #{[:value [:b] 16 nil]
                        [:node-destroy [:b] :map]}}
            {:input {msg/topic :a :n 18} :emitter #{}}
            {:input {msg/topic :b :n 19} :emitter #{}}
            {:input {msg/topic :c :n 20} :emitter #{[:value [:c] 17 20]}}]))))
