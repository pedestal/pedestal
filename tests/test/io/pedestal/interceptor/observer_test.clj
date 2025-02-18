; Copyright 2024-2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0
; which can be found in the file epl-v10.html at the root of this distri
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor.observer-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.interceptor.chain.debug :as debug]
            [clojure.core.async :refer [chan go close!]]
            [io.pedestal.log :as log]
            [mockfn.macros :as mock]
            [mockfn.matchers :as m]
            [io.pedestal.test-common :refer [<!!?]]
            [io.pedestal.interceptor :refer [interceptor]]))

(def *events (atom []))

(use-fixtures :each
              (fn [f]
                (swap! *events empty)
                (f)))

(defn- event-observer
  [event]
  (swap! *events conj event))

(defn- names-and-stages
  []
  (mapv (juxt :interceptor-name :stage) @*events))

(defn- execute
  [context & interceptors]
  (chain/execute context (mapv interceptor interceptors)))

(deftest observes-each-stage
  (execute (chain/add-observer nil event-observer)
           {:name  ::outer
            :enter identity
            :leave identity}
           {:name  ::middle
            :leave identity}
           {:name  ::inner
            :enter identity})

  (is (= [[::outer :enter]
          [::inner :enter]
          [::middle :leave]
          [::outer :leave]]
         (names-and-stages))))

(deftest observer-each-stage-async
  (let [done-ch        (chan)
        async-identity (fn [context]
                         (go context))]
    (execute (chain/add-observer nil event-observer)
             {:name  ::terminate
              :leave (fn [context]
                       (close! done-ch)
                       context)}
             {:name  ::outer
              :enter async-identity
              :leave async-identity}
             {:name  ::middle
              :leave async-identity}
             {:name  ::inner
              :enter async-identity})

    (is (nil? (<!!? done-ch)))

    (is (= [[::outer :enter]
            [::inner :enter]
            [::middle :leave]
            [::outer :leave]
            [::terminate :leave]]
           (names-and-stages)))))

(deftest observes-errors
  (execute (chain/add-observer nil event-observer)
           {:name  ::outer
            :enter identity
            :error (fn [context _]
                     context)}
           {:name  ::middle
            :enter identity
            :leave identity}
           {:name  ::failure
            :enter (fn [_context]
                     (throw (RuntimeException. "Ignore: just testing exception handling")))})
  (is (= [[::outer :enter]
          [::middle :enter]
          ;; No notification for ::failure interceptor as the exception occurred there.
          ;; No notification for ::middle, as it doesn't provide an :error handler.
          [::outer :error]]
         (names-and-stages))))

(deftest old-and-new-contexts-are-passed
  (execute (chain/add-observer nil event-observer)
           {:name  ::outer
            :enter #(assoc % :foo :bar)
            :leave identity}
           {:name  ::inner
            :leave #(assoc % :gnip :gnop)})

  (is (= [[::outer :enter]
          [::inner :leave]
          [::outer :leave]]
         (names-and-stages)))

  (is (match?
        [{:context-out {:foo :bar}}
         {:context-in  {:foo :bar}
          :context-out {:foo  :bar
                        :gnip :gnop}}
         {:context-in  {:foo  :bar
                        :gnip :gnop}
          :context-out {:foo  :bar
                        :gnip :gnop}}]
        @*events)))

(deftest calls-multiple-observers
  (let [*events-1 (atom [])
        *events-2 (atom [])
        context   (-> {}
                      (chain/add-observer #(swap! *events-1 conj %))
                      (chain/add-observer #(swap! *events-2 conj %)))]
    (execute context
             {:name  ::outer
              :enter identity
              :leave identity}
             {:name  ::middle
              :leave identity}
             {:name  ::inner
              :enter identity})

    (is (= 4 (count @*events-1)))

    (is (= @*events-1 @*events-2))))

(deftest can-add-observers-mid-stream
  (execute {}
           {:name  ::outer
            :enter identity
            :leave identity}
           ;; The notification is based on the new context returned by this interceptor, so
           ;; the ::observer :enter stage will be visible to the observer function.
           {:name  ::observer
            :enter #(chain/add-observer % event-observer)}
           {:name  ::middle
            :leave identity}
           {:name  ::inner
            :enter identity})


  (is (= [[::observer :enter]
          [::inner :enter]
          [::middle :leave]
          [::outer :leave]]
         (names-and-stages))))

(def capture-logger
  (reify log/LoggerSource

    (-level-enabled? [_ level] (= level :debug))

    (-debug [_ body]
      (swap! *events conj (edn/read-string body)))))

(deftest debug-observer
  (mock/providing [(log/make-logger "io.pedestal.interceptor.chain.debug") capture-logger
                   (log/make-logger (m/any)) mock/fall-through]
    (execute (chain/add-observer nil (debug/debug-observer {:omit #{[:response :body]
                                                                    [:sensitive]}}))
             {:name  ::content-type
              :leave #(assoc-in % [:response :headers "Content-Type"] "application/edn")}
             {:name  ::change-status
              :leave #(assoc-in % [:response :status] 303)}
             {:name  ::do-nothing
              :leave identity}
             ;; A bunch of changes to the body which is omitted
             {:name  ::restore-body
              :leave #(assoc-in % [:response :body] "ZZZ")}
             {:name  ::delete-body
              :leave #(update % :response dissoc :body)}
             {:name  ::rewrite-body-again
              :leave #(assoc-in % [:response :body] "YYY")}
             {:name  ::rewrite-body
              :leave #(assoc-in % [:response :body] "XXX")}
             {:name  ::handler
              :enter #(assoc % :response {:status 200 :body {:message "OK"}})})
    (is (match?
          '[{:interceptor     ::handler
             :stage           :enter
             :context-changes {:added {[:response] {:status 200
                                                    :body   ...}}}}
            {:interceptor     ::rewrite-body
             :stage           :leave
             :context-changes {:changed {[:response :body] ...}}}
            {:interceptor     ::rewrite-body-again
             :stage           :leave
             :context-changes {:changed {[:response :body] ...}}}
            {:interceptor     ::delete-body
             :stage           :leave
             :context-changes {:removed {[:response :body] ...}}}
            {:interceptor     ::restore-body
             :stage           :leave
             :context-changes {:added {[:response :body] ...}}}
            {:interceptor     ::do-nothing
             :stage           :leave
             :context-changes nil}
            {:interceptor     ::change-status
             :stage           :leave
             :context-changes {:changed {[:response :status] {:from 200 :to 303}}}}
            {:interceptor     ::content-type
             :stage           :leave
             :context-changes {:added {[:response :headers] {"Content-Type" "application/edn"}}}}]
         @*events))))

(deftest debug-observer-changes-only
  (mock/providing [(log/make-logger "io.pedestal.interceptor.chain.debug") capture-logger
                   (log/make-logger (m/any)) mock/fall-through]
    (execute (chain/add-observer nil (debug/debug-observer {:changes-only? true}))
             {:name  ::first
              :enter #(assoc % :active :first)}
             {:name  ::second
              :enter identity}
             {:name  ::third
              :enter #(assoc % :active :third)})
    (is (match? [{:interceptor     ::first
                  :context-changes {:added {[:active] :first}}}
                 {:interceptor     ::third
                  :context-changes {:changed {[:active] {:from :first
                                                         :to   :third}}}}] @*events))))
(def delta #'debug/delta)

(deftest debug-reports-ommitted-on-change
  (is (match? '{:changed {[:sensitive] ...}}
              (delta #{[:sensitive]}
                     {:sensitive {:value :from}}
                     {:sensitive {:value :to}}))))

(deftest omitted-ignore-if-not-changed
  (is (match? '{:changed {[:other] {:from :before :to :after}}}
              (delta #{[:sensitive]}
                     {:sensitive {:value :stable}
                      :other     :before}
                     {:sensitive {:value :stable}
                      :other     :after}))))

(deftest observe-via-tap
  (let [*taps (atom [])
        tap   (fn [v] (swap! *taps conj v))]
    (with-redefs [tap> tap]
      (let [observer (debug/debug-observer {:tap? true})]
        (observer {:execution-id     ::id
                   :stage            ::stage
                   :interceptor-name ::interceptor
                   :context-in       {}
                   :context-out      {::key ::value}})
        (is (match?
              [{:execution-id    ::id
                :stage           ::stage
                :interceptor     ::interceptor
                :context-changes {:added {[::key] ::value}}
                }]
              @*taps))))))
