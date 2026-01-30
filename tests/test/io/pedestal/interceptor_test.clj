; Copyright 2024-2026 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor-test
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.internal :as i]
            [clojure.core.async :as async
             :refer [<! >! go chan timeout <!! put!]]
            [io.pedestal.test-common :refer [<!!?]]
            [io.pedestal.interceptor :as interceptor :refer [interceptor]]
            [io.pedestal.interceptor.chain :as chain :refer [execute enqueue]])
  (:import (clojure.lang Keyword)
           (java.util.concurrent CountDownLatch TimeUnit)))

(defn trace
  [context direction name]
  (update context ::trace i/vec-conj [direction name]))

(defn tracer [name]
  (interceptor {:name  name
                :enter #(trace % :enter name)
                :leave #(trace % :leave name)}))

(defn thrower [name]
  (assoc (tracer name)
         :enter (fn [_context] (throw (ex-info "Boom!" {:from name})))))

(defn catcher [name]
  (assoc (tracer name)
         :error (fn [context error]
                  (update context ::trace i/vec-conj
                          [:error name :from (:from (ex-data error))]))))

(defn channel-callback [stage name]
  (fn [context]
    (let [context* (-> (trace context stage name)
                       (update ::thread-ids i/vec-conj (.. Thread currentThread getId)))]
      (go
        (<! (timeout 100))
        context*))))

(defn channeler [name]
  (assoc (tracer name)
         :enter (channel-callback :enter name)))

(defn failed-channeler [name]
  (assoc (tracer name)
         :enter (fn [_context]
                  (go
                    (<! (timeout 100))
                    (throw (ex-info "This gets swallowed and the channel produced by `go` is closed"
                                    {:from name}))))))


(defn deliverer [ch]
  (interceptor {:name  ::deliverer
                :leave #(do (put! ch %)
                            ch)}))

(deftest t-simple-execution
  (let [expected   {::trace [[:enter :a]
                             [:enter :b]
                             [:enter :c]
                             [:leave :c]
                             [:leave :b]
                             [:leave :a]]}
        actual-en  (execute (enqueue {}
                                     [(tracer :a)
                                      (tracer :b)
                                      (tracer :c)]))
        actual-en* (execute (chain/enqueue* {}
                                            (tracer :a)
                                            (tracer :b)
                                            (tracer :c)))
        actual-ex  (execute {} [(tracer :a)
                                (tracer :b)
                                (tracer :c)])]
    (is (match? expected actual-en))
    (is (match? expected actual-en*))
    (is (match? expected actual-ex))))


(deftest t-error-propagates
  (is (thrown? Exception
               (execute (enqueue {}
                                 [(tracer :a)
                                  (tracer :b)
                                  (thrower :c)
                                  (tracer :d)])))))

(deftest t-error-caught-in-execute
  (is (match? {::trace [[:enter :a]
                        [:enter :b]
                        [:enter :c]
                        [:enter :d]
                        [:enter :e]
                        [:error :c :from :f]
                        [:leave :b]
                        [:leave :a]]}
              (execute (enqueue {}
                                [(tracer :a)
                                 (tracer :b)
                                 (catcher :c)
                                 (tracer :d)
                                 (tracer :e)
                                 (thrower :f)
                                 (tracer :g)])))))


(deftest t-enqueue
  (is (thrown? AssertionError
               (enqueue {} [nil]))
      "nil is not an interceptor")
  (is (thrown? AssertionError
               (enqueue {} [(fn [_])]))
      "function is not an interceptor")
  (is (chain/queue (enqueue {} [(tracer :a)]))
      "enqueue interceptor to empty queue")
  (is (thrown? AssertionError
               (enqueue {} [(tracer :a) nil]))
      "enqueue one invalid interceptor to empty queue")
  (is (thrown? AssertionError
               (enqueue {} [(fn [_]) (tracer :b)]))
      "enqueue one invalid interceptor to empty queue")
  (is (chain/queue (enqueue {} [(tracer :a) (tracer :b)]))
      "enqueue multiple interceptors to empty queue")
  (is (chain/queue (-> {}
                       (enqueue [(tracer :a)])
                       (enqueue [(tracer :b)])))
      "enqueue to non-empty queue")
  (is (thrown? AssertionError
               (-> {}
                   (enqueue [(tracer :a)])
                   (enqueue [nil])))
      "enqueue invalid to non-empty queue"))

(deftest t-two-channels
  (let [result-chan (chan)
        _           (execute (enqueue {}
                                      [(deliverer result-chan)
                                       (tracer :a)
                                       (channeler :b)
                                       (channeler :c)
                                       (tracer :d)]))
        result      (<!! result-chan)
        trace       (result ::trace)
        thread-ids  (result ::thread-ids)]
    (is (match? [[:enter :a]
                 [:enter :b]
                 [:enter :c]
                 [:enter :d]
                 [:leave :d]
                 [:leave :c]
                 [:leave :b]
                 [:leave :a]]
                trace))
    (is (= 2
           (-> thread-ids distinct count)))))

(deftest t-two-channels-with-error
  (let [result-chan (chan)
        _           (execute (enqueue {}
                                      [(deliverer result-chan)
                                       (tracer :a)
                                       (catcher :b)
                                       (channeler :c)
                                       (tracer :d)
                                       (thrower :e)
                                       (tracer :f)]))
        result      (<!! result-chan)
        trace       (result ::trace)
        thread-ids  (result ::thread-ids)]
    (is (match? [[:enter :a]
                 [:enter :b]
                 [:enter :c]
                 [:enter :d]
                 ;; :e throws, gets caught by :b
                 [:error :b :from :e]
                 ;; Finish and unwind the stack
                 [:leave :a]]
                trace))
    (is (= 1
           (-> thread-ids distinct count)))))

(deftest failed-channel-produces-error
  (println "This test will produce an uncaught exception, which can be ignored.")
  (let [result-chan (chan)
        result      (execute (enqueue {}
                                      [(deliverer result-chan)
                                       (tracer :a)
                                       (catcher :b)
                                       ;; Will, on :enter, throw an exception
                                       ;; that will be logged the console and
                                       ;; should bubble up to :b.
                                       (failed-channeler :c)
                                       (tracer :d)]))]
    ;; Check that when execution goes async, the original caller
    ;; is returned a nil.
    (is (nil? result))
    (is (match? {::trace [[:enter :a]
                          [:enter :b]
                          [:error :b :from nil]
                          [:leave :a]]}
                (<!!? result-chan)))))


(deftest termination
  (let [context (chain/terminate-when {} (fn [ctx]
                                           (some #{[:enter :b]} (::trace ctx))))
        _       (execute context
                         [(tracer :a)
                          (tracer :b)
                          (tracer :c)])]

    (testing "Async termination"
      (let [result-chan (chan)
            _           (execute context
                                 [(deliverer result-chan)
                                  (tracer :a)
                                  (channeler :b)
                                  (tracer :c)])
            result      (<!! result-chan)]
        (is (= (::trace result) [[:enter :a] [:enter :b] [:leave :b] [:leave :a]]))))))

(def around-interceptor
  "An interceptor that does the around pattern."
  (interceptor
    {:enter (fn [context] (assoc context :around :enter))
     :leave (fn [context] (assoc context :around :leave))}))

(deftest test-around-interceptor
  (is (interceptor/interceptor? around-interceptor)
      "defaround creates an interceptor.")
  ;(is (= true (:interceptor (meta #'around-interceptor)))
  ;    "The defined interceptor is tagged as an interceptor in metadata.")
  (is (= :enter (-> {}
                    ((:enter around-interceptor))
                    :around))
      "The first fn body gets executed during the enter stage.")
  (is (= :leave (-> {}
                    ((:leave around-interceptor))
                    :around))
      "The second fn body gets executed during the leave stage."))

(def middleware-interceptor
  "An interceptor that does the middleware pattern."
  (interceptor
    {:enter #(assoc-in % [:request :middleware] :enter)
     :leave #(assoc-in % [:response :middleware] :leave)}))

(deftest test-middleware-interceptor
  (is (interceptor/interceptor? middleware-interceptor)
      "defmiddleware creates an interceptor.")
  ;(is (= true (:interceptor (meta #'middleware-interceptor)))
  ;    "The defined interceptor is tagged as an interceptor in metadata.")
  (is (= :enter (-> {:request {}}
                    ((:enter middleware-interceptor))
                    :request
                    :middleware))
      "The first fn body gets executed with the value of request during the enter stage.")
  (is (= :leave (-> {:response {}}
                    ((:leave middleware-interceptor))
                    :response
                    :middleware))))

;; error suppression test

(def failing-interceptor
  (interceptor
    {:name  ::failing-interceptor
     :enter (fn [_ctx]
              (/ 1 0))}))

(def rethrowing-error-handling-interceptor
  (interceptor
    {:name  ::rethrowing-error-handling-interceptor
     :error (fn [_ctx ex]
              ;; Rethrow the wrapper, not the original exception
              (throw ex))}))

(def throwing-error-handling-interceptor
  (interceptor
    {:name  ::throwing-error-handling-interceptor
     :error (fn [_ctx _ex]
              (throw (Exception. "Just testing the error-handler, this is not a real exception")))}))

(def error-handling-interceptor
  (interceptor
    {:name  ::error-handling-interceptor
     :error (fn [ctx ex] (assoc ctx :caught-exception ex))}))

(deftest throwing-the-exception-is-not-a-suppression
  (let [ctx (execute {} [error-handling-interceptor
                         rethrowing-error-handling-interceptor
                         failing-interceptor])]
    ;; When an exception handling interceptor re-throws the existing exception,
    ;; then the original wrapped exception propagates up.
    (is (match? {:stage       :enter
                 :interceptor ::failing-interceptor}
                (-> ctx :caught-exception ex-data)))
    (is (= 0 (-> (ctx ::chain/suppressed) count)))))


(deftest chain-execution-error-suppression-test
  (is (nil? (::chain/suppressed (execute {} [error-handling-interceptor failing-interceptor])))
      "The `io.pedestal.interceptor.chain/suppressed` key should not be set when an exception is handled.")

  (let [ctx (execute {} [error-handling-interceptor
                         throwing-error-handling-interceptor
                         failing-interceptor])]
    ;; Check that correct data is captured; the error handling interceptor was invoked
    ;; in stage :error and threw a new exception.
    (is (match? {:stage          :error
                 :interceptor    ::throwing-error-handling-interceptor
                 :exception-type :java.lang.Exception}
                (-> ctx :caught-exception ex-data)))

    (is (= 1 (count (::chain/suppressed ctx)))
        "There should be a suppressed error when a different exception is thrown")

    (is (match? {:exception-type :java.lang.ArithmeticException
                 :stage          :enter
                 :interceptor    ::failing-interceptor}
                (-> ctx ::chain/suppressed first ex-data))
        "The suppressed exception should be the original exception wrapper")))

(def ^:dynamic *bindable* :default)

(deftest bound-vars-available-from-async-interceptors
  (let [*events      (atom [])
        chan         (chan)
        observer     (fn [name stage async?]
                       (let [f (fn [context]
                                 (swap! *events conj {:name name :stage stage :value *bindable*})
                                 context)]
                         (interceptor {:name name
                                       stage (if async?
                                               #(go (f %))
                                               f)})))
        interceptors [(interceptor {:name  ::unlock
                                    :leave (fn [context]
                                             (async/close! chan)
                                             context)})
                      (observer :a :enter false)
                      (observer :b :leave false)
                      (interceptor {:name  :first
                                    :enter #(chain/bind % *bindable* :first)})
                      (observer :c :enter true)
                      (interceptor {:name  :second
                                    :enter #(go (chain/bind % *bindable* :second))})
                      (observer :d :enter false)
                      (observer :e :leave true)]]
    (execute {} interceptors)
    (is (nil? (<!!? chan)))

    (is (match? [{:name :a :stage :enter :value :default}
                 ;; :first
                 {:name :c :stage :enter :value :first}
                 ;; :second
                 {:name :d :stage :enter :value :second}
                 ;; :third
                 {:name :e :stage :leave :value :second}
                 {:name :b :stage :leave :value :second}]
                @*events))))

(deftest enter-async-invoked-only-once
  (let [*count (atom 0)
        f      (fn [_] (swap! *count inc))
        enter  (fn [context]
                 (go context))]
    (-> {}
        (chain/on-enter-async f)
        (execute [(interceptor {:name :a :enter enter})
                  (interceptor {:name :b :enter enter})]))
    (is (= 1 @*count))))

(def ^:dynamic *rebindable* nil)

(def custom-handler ^{:name ::custom} (fn [_request] ::custom-response))

(defn anon-handler [_request] ::anon-response)

(deftest handler-to-interceptor-uses-name-meta-key
  (let [interceptor (interceptor custom-handler)]
    (is (= {:response ::custom-response} ((-> interceptor :enter) nil)))
    (is (= ::custom (:name interceptor)))))

(deftest handler-to-interceptor-default-name
  (let [interceptor (interceptor anon-handler)]
    (is (= {:response ::anon-response} ((-> interceptor :enter) nil)))
    (is (= ::anon-handler (:name interceptor)))))

(deftest default-name-from-inline
  ;; The extra "fn/" prefix is actually part of deftest
  (is (= (keyword "io.pedestal.interceptor-test" "fn/foo")
         (:name (interceptor (fn foo [_request] nil))))))

(deftest interceptor-leave-ordering-after-a-change-in-bindings
  (let [step     (fn [interceptor-name]
                   (interceptor
                     {:name  interceptor-name
                      :leave (fn [context]
                               (-> context
                                   (update :order i/vec-conj interceptor-name)
                                   (assoc-in [:peek interceptor-name] *rebindable*)))}))

        rebinder (fn [value]
                   (interceptor
                     {:leave (fn [context]
                               (chain/bind context *rebindable* value))}))]
    ;; This is the expected leave order
    (is (match? {:order [:d :c :b :a]}
                (execute nil
                         [(step :a)
                          (step :b)
                          (step :c)
                          (step :d)])))

    (is (match? {:order [:f :e :d :c :b :a]
                 :peek  {:f nil
                         :e nil
                         :d :early
                         :c :early
                         :b :late
                         :a :late}}
                (execute nil
                         [(step :a)
                          (step :b)
                          (rebinder :late)
                          (step :c)
                          (step :d)
                          (rebinder :early)
                          (step :e)
                          (step :f)])))))


(deftest interceptor-leave-ordering-after-a-change-in-bindings-async
  (let [step     (fn [interceptor-name]
                   (interceptor
                     {:name  interceptor-name
                      :leave (fn [context]
                               (go
                                 (-> context
                                     (update :order i/vec-conj interceptor-name)
                                     (assoc-in [:peek interceptor-name] *rebindable*))))}))

        rebinder (fn [value]
                   (interceptor
                     {:leave (fn [context]
                               (go
                                 (chain/bind context *rebindable* value)))}))
        ch       (chan 1)
        capture  (interceptor
                   {:name  :capture
                    :leave (fn [context]
                             (go
                               (>! ch context)
                               context))})]

    (execute nil
             [capture
              (step :a)
              (step :b)
              (step :c)
              (step :d)])

    ;; This is the expected leave order

    (is (match? {:order [:d :c :b :a]}
                (<!!? ch)))

    (execute nil
             [capture
              (step :a)
              (step :b)
              (rebinder :late)
              (step :c)
              (step :d)
              (rebinder :early)
              (step :e)
              (step :f)])

    (is (match? {:order [:f :e :d :c :b :a]
                 :peek  {:f nil
                         :e nil
                         :d :early
                         :c :early
                         :b :late
                         :a :late}}
                (<!!? ch)))))

(defn async-handler
  [_request]
  (go ::response))

(deftest handler-can-return-channel
  (let [*capture (atom nil)
        latch    (CountDownLatch. 1)
        capturer (interceptor {:name  ::capturer
                               :leave (fn [context]
                                        (reset! *capture (:response context))
                                        (.countDown latch)
                                        context)})
        context  (execute nil [capturer
                               (interceptor async-handler)])]
    (is (nil? context)
        "nil context when it goes async")

    (is (= true (.await latch 1 TimeUnit/SECONDS)))

    (is (= ::response @*capture))))

(deftest interceptor-name-must-be-a-keyword
  (when-let [e (is (thrown? Exception (interceptor/interceptor-name "not-a-keyword")))]
    (is (= "Name must be keyword or nil; Got: \"not-a-keyword\"" (ex-message e)))
    (is (= {:name "not-a-keyword"}
           (ex-data e)))))

(deftest does-not-satisfy-into-interceptor
  (when-let [e (is (thrown-with-msg? Exception #"isn't supported by the protocol"
                                     (interceptor :just-a-keyword)))]
    (is (= {:t    :just-a-keyword
            :type Keyword}
           (ex-data e)))))

(deftest enqeue*-will-expand-a-list-as-final-value
  (let [names (fn [context] (->> context
                                 chain/queue
                                 (map :name)))
        ctx   (enqueue {} [(tracer :a)])]
    (is (= [:a]
           (names ctx)))

    (is (= [:a :b :c :d]
           (names
             (chain/enqueue* ctx
                             (tracer :b)
                             (tracer :c)
                             (tracer :d)))))

    (is (= [:a :b :c :d]
           (names
             (chain/enqueue* ctx
                             (tracer :b)
                             [(tracer :c) (tracer :d)]))))))
