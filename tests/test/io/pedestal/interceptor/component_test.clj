; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor.component-test
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.interceptor :refer [interceptor definterceptor]]
            [com.stuartsierra.component :as component]
            [io.pedestal.interceptor.chain :as chain]
            [matcher-combinators.matchers :as m]))

(set! *warn-on-reflection* true)

(defprotocol Track
  (track [this event]))

(defrecord Tracker [*events]

  Track

  (track [_ event]
    (swap! *events conj event)
    nil)

  component/Lifecycle

  (start [this]
    (assoc this :*events (atom [])))

  (stop [this] this))

(defn events [context]
  (-> context :system :tracker :*events deref))

(definterceptor jack [tracker]

  (enter [_ context]
    (track tracker [:enter :jack])
    context)

  (leave [_ context]
    (track tracker [:leave :jack])
    context))

(definterceptor handler [tracker]

  (handle [_ request]
    (track tracker [:handle :handler request])
    ::response))

(definterceptor fail [ex tracker]

  (enter [_ _]
    (track tracker [:enter :fail (ex-data ex)])
    (throw ex)))

(definterceptor fixer [ex tracker]

  (error [_ context thrown-ex]
    (track tracker [:error :fixer (ex-data thrown-ex)])
    (is (identical? ex (ex-cause thrown-ex)))
    (chain/clear-error context)))

(definterceptor mixed [*context]

  (enter [_ context]
    (reset! *context context)
    context)

  component/Lifecycle

  (start [this]
    (assoc this :*context (atom nil)))

  (stop [this] this))


(defn- execute [system-map base-context & interceptor-keys]
  (let [system       (component/start-system system-map)
        interceptors (->> (map #(get system %) interceptor-keys)
                          (map interceptor))]
    (chain/execute (assoc base-context :system system) interceptors)))

(deftest enter-and-leave
  (let [system  (component/system-map
                  :tracker (map->Tracker {})
                  :jack (component/using (map->jack {})
                                         [:tracker]))
        context (execute system nil :jack)]


    (is (match? [[:enter :jack]
                 [:leave :jack]] (events context)))))

(deftest error-handling
  (let [ex-data {:component :failure}
        ex      (ex-info "component failure" ex-data)
        system  (component/system-map
                  :tracker (map->Tracker {})
                  :jack (component/using (map->jack {})
                                         [:tracker])
                  :fail (component/using (map->fail {:ex ex})
                                         [:tracker])
                  :fixer (component/using (map->fixer {:ex ex})
                                          [:tracker]))
        context (execute system nil :jack :fixer :fail)]
    (is (match? [[:enter :jack]
                 ;; :fixer doesn't have an :enter
                 [:enter :fail {:component :failure}]
                 [:error :fixer {:component :failure}]
                 [:leave :jack]]
                (events context)))))

(deftest handler-protocol
  (let [system  (component/system-map
                  :tracker (map->Tracker {})
                  :handler (component/using (map->handler {})
                                            [:tracker]))
        context (execute system {:request ::request} :handler)]
    (is (match? {:request  ::request
                 :response ::response}
                context))

    (is (match? [[:handle :handler ::request]]
                (events context)))))

(deftest supports-other-protocols
  (let [system       (component/system-map
                       :mixed (map->mixed {}))
        init-context {:context :init}
        context      (execute system init-context :mixed)]
    (is (match? init-context context))
    (is (match? init-context
                (-> context :system :mixed :*context deref)))))

(deftest interceptor-name-is-namespace-qualified
  (is (= ::jack
         (-> (map->jack {}) interceptor :name))))

(deftest does-not-allow-unknown-methods

  (when-let [e (is (thrown? Exception
                            (eval
                              '(io.pedestal.interceptor/definterceptor unknown [] (unknown [_ _] nil)))))]

    (is (= "Unexpected method: unknown" (-> e ex-cause ex-message)))
    (is (match? {:method        '(unknown [_ _] nil)
                 :valid-methods (m/via set #{'handle 'enter 'leave 'error})}
                (-> e ex-cause ex-data)))))

(deftest unexpected-spec-value

  (when-let [e (is (thrown? Exception
                            (eval
                              '(io.pedestal.interceptor/definterceptor unknown [] :whazzis (foo [_])))))]

    (is (= "Unexpected value for record spec: :whazzis"
           (-> e ex-cause ex-message)))
    (is (match? {:value     :whazzis
                 :remaining '[(foo [_])]}
                (-> e ex-cause ex-data)))))




