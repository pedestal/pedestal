; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.interceptor-test
  (:require [clojure.test :refer (deftest is)]
            [io.pedestal.service.interceptor :as interceptor
             :refer (definterceptor definterceptorfn interceptor defaround defmiddleware)]
            [io.pedestal.service.impl.interceptor :as impl
             :refer (execute enqueue with-pause resume)]))

(defn trace [context direction name]
  (update-in context [::trace] (fnil conj []) [direction name]))

(definterceptorfn tracer [name]
  (interceptor
   :name name
   :enter #(trace % :enter name)
   :leave #(trace % :leave name)
   :pause #(trace % :pause name)
   :resume #(trace % :resume name)))

(definterceptorfn thrower [name]
  (assoc (tracer name)
    :enter (fn [context] (throw (ex-info "Boom!" {:from name})))))

(definterceptorfn catcher [name]
  (assoc (tracer name)
    :error (fn [context error]
             (update-in context [::trace] (fnil conj [])
                        [:error name :from (:from (ex-data error))]))))

(definterceptorfn delayer [name]
  (assoc (tracer name)
    :enter (fn [context]
             (with-pause [context* (trace context :enter name)]
               (future (Thread/sleep 100)
                       (resume context*))))))

(definterceptorfn deliverer [p]
  (interceptor :name 'deliverer
               :leave #(deliver p (dissoc % ::impl/stack ::impl/execution-id))))

(deftest t-simple-execution
  (is (= {::trace [[:enter :a]
                   [:enter :b]
                   [:enter :c]
                   [:leave :c]
                   [:leave :b]
                   [:leave :a]]}
         (execute (enqueue {}
                           (tracer :a)
                           (tracer :b)
                           (tracer :c))))))

(deftest t-error-propagates
  (is (thrown? Exception
               (execute (enqueue {}
                                 (tracer :a)
                                 (tracer :b)
                                 (thrower :c)
                                 (tracer :d))))))

(deftest t-error-caught
  (is (= {::trace [[:enter :a]
                   [:enter :b]
                   [:enter :c]
                   [:enter :d]
                   [:enter :e]
                   [:error :c :from :f]
                   [:leave :b]
                   [:leave :a]]}
         (execute (enqueue {}
                           (tracer :a)
                           (tracer :b)
                           (catcher :c)
                           (tracer :d)
                           (tracer :e)
                           (thrower :f)
                           (tracer :g))))))

(deftest t-simple-async
  (let [p (promise)]
    (execute (enqueue {}
                      (deliverer p)
                      (tracer :a)
                      (tracer :b)
                      (delayer :c)
                      (tracer :d)))
    (is (= {::trace [[:enter :a]
                     [:enter :b]
                     [:enter :c]
                     [:pause :c]
                     [:pause :b]
                     [:pause :a]
                     [:resume :a]
                     [:resume :b]
                     [:resume :c]
                     [:enter :d]
                     [:leave :d]
                     [:leave :c]
                     [:leave :b]
                     [:leave :a]]}
           @p))))

(deftest t-pause-twice
  (let [p (promise)]
    (execute (enqueue {}
                      (deliverer p)
                      (tracer :a)
                      (tracer :b)
                      (delayer :c)
                      (tracer :d)
                      (delayer :e)
                      (tracer :f)))
    (is (= {::trace [[:enter :a]
                     [:enter :b]
                     [:enter :c]
                     ;; :c pauses
                     [:pause :c]
                     [:pause :b]
                     [:pause :a]
                     ;; :c resumes
                     [:resume :a]
                     [:resume :b]
                     [:resume :c]
                     ;; Continue with :d
                     [:enter :d]
                     [:enter :e]
                     ;; :e pauses
                     [:pause :e]
                     [:pause :d]
                     [:pause :c]
                     [:pause :b]
                     [:pause :a]
                     ;; :e resumes
                     [:resume :a]
                     [:resume :b]
                     [:resume :c]
                     [:resume :d]
                     [:resume :e]
                     ;; Continue with :f
                     [:enter :f]
                     ;; Finish and unwind the stack
                     [:leave :f]
                     [:leave :e]
                     [:leave :d]
                     [:leave :c]
                     [:leave :b]
                     [:leave :a]]}
           @p))))

(deftest t-async-with-error
  (let [p (promise)]
    (execute (enqueue {}
                      (deliverer p)
                      (tracer :a)
                      (catcher :b)
                      (delayer :c)
                      (tracer :d)
                      (thrower :e)
                      (tracer :f)))
    (is (= {::trace [[:enter :a]
                     [:enter :b]
                     [:enter :c]
                     ;; :c pauses
                     [:pause :c]
                     [:pause :b]
                     [:pause :a]
                     ;; :c resumes
                     [:resume :a]
                     [:resume :b]
                     [:resume :c]
                     ;; Continue with :d
                     [:enter :d]
                     ;; :e throws, gets caught by :b
                     [:error :b :from :e]
                     ;; Finish and unwind the stack
                     [:leave :a]]}
           @p))))

(defaround around-interceptor
  "An interceptor that does the around pattern."
  ([context] (assoc context :around :enter))
  ([context] (assoc context :around :leave)))

(deftest test-around-interceptor
  (is (= io.pedestal.service.impl.interceptor.Interceptor (type around-interceptor))
      "defaround creates an interceptor.")
  (is (= true (:interceptor (meta #'around-interceptor)))
      "The defined interceptor is tagged as an interceptor in metadata.")
  (is (= :enter (-> {}
                    ((:enter around-interceptor))
                    :around))
      "The first fn body gets executed during the enter stage.")
  (is (= :leave (-> {}
                    ((:leave around-interceptor))
                    :around))
      "The second fn body gets executed during the leave stage."))

(defmiddleware middleware-interceptor
  "An interceptor that does the middleware pattern."
  ([request] (assoc request :middleware :enter))
  ([response] (assoc response :middleware :leave)))

(deftest test-middleware-interceptor
  (is (= io.pedestal.service.impl.interceptor.Interceptor (type middleware-interceptor))
      "defmiddleware creates an interceptor.")
  (is (= true (:interceptor (meta #'middleware-interceptor)))
      "The defined interceptor is tagged as an interceptor in metadata.")
  (is (= :enter (-> {:request {}}
                    ((:enter middleware-interceptor))
                    :request
                    :middleware))
      "The first fn body gets executed with the value of request during the enter stage.")
  (is (= :leave (-> {:response {}}
                    ((:leave middleware-interceptor))
                    :response
                    :middleware))))
