(ns io.pedestal.interceptor.observer-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.core.async :refer [chan go close!]]
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
          [::outer :leave]])
      (names-and-stages))

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
        context (-> {}
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
