; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.metrics.otel-test
  (:require [io.pedestal.internal :as i]
            [io.pedestal.metrics :as metrics]
            [matcher-combinators.matchers :as m]
            [io.pedestal.metrics.otel :as otel]
            [clojure.test :refer [deftest is are use-fixtures]])
  (:import (clojure.lang ExceptionInfo)
           (io.opentelemetry.api.metrics DoubleGaugeBuilder LongCounter LongCounterBuilder LongGaugeBuilder Meter
                                         ObservableLongGauge ObservableLongMeasurement)
           (java.util.function Consumer)))

(def *now (atom 0))

(def *events (atom nil))

(defn- events
  "Returns value of **events before clearing it."
  []
  (let [result @*events]
    (reset! *events [])
    result))

(defn- event [& args]
  (swap! *events i/vec-conj (vec args)))

(defn mock-counter-builder
  [name]
  (reify

    LongCounterBuilder

    (setDescription [this description]
      (event :setDescription name description)
      this)

    (setUnit [this unit]
      (event :setUnit name unit)
      this)

    (build [this]
      (event :build-counter name)
      this)

    LongCounter

    (add [_ value]
      (event :add name value))

    (add [_ value attributes]
      (event :add name value attributes))))


(defn mock-long-gauge-builder
  [name]
  (reify
    LongGaugeBuilder

    (setDescription [this description]
      (event :setDescription name description)
      this)

    (setUnit [this unit]
      (event :setUnit name unit)
      this)

    (buildWithCallback [this callback]
      (event :buildWithCallback name callback)
      this)

    ObservableLongGauge))

(defn mock-double-gauge-builder
  [name]
  (reify
    DoubleGaugeBuilder

    (ofLongs [_]
      (event :ofLongs name)
      (mock-long-gauge-builder name))))

(def mock-meter
  (reify

    Meter

    (counterBuilder [_ name]
      (mock-counter-builder name))

    (gaugeBuilder [_ name]
      (mock-double-gauge-builder name))))

(defn mock-metric-fixture
  [f]
  (binding [metrics/*default-metric-source* (otel/wrap-meter mock-meter)]
    (reset! *events [])
    (f)))

(use-fixtures :each mock-metric-fixture)

(def convert-metric-name @#'otel/convert-metric-name)

(def convert-key @#'otel/convert-key)

(def empty-attributes (m/via str "{}"))

(def clojure-domain-attributes (m/via str "{domain=\"clojure\"}"))

(deftest counter-by-keyword-and-string-name-are-the-same
  (let [metric-name "foo.bar.baz"
        f1          (metrics/counter (keyword metric-name) nil)
        f2          (metrics/counter metric-name nil)]
    ;; Counter gets built twice (but with same name), since cached on
    ;; the application supplied key.
    (is (match? [[:build-counter "foo.bar.baz"]
                 [:build-counter "foo.bar.baz"]]
                (events)))

    (f1)

    (is (match? [[:add "foo.bar.baz" 1 empty-attributes]]
                (events)))

    (f2 5)

    (is (match? [[:add "foo.bar.baz" 5 empty-attributes]]
                (events)))))

(deftest counter-with-description-and-unit
  (let [metric-name :gnip.gnop
        f           (metrics/counter metric-name
                                     {::metrics/description "description"
                                      ::metrics/unit        "unit"
                                      :domain               "clojure"})]
    (is (match? [[:setDescription "gnip.gnop" "description"]
                 [:setUnit "gnip.gnop" "unit"]
                 [:build-counter "gnip.gnop"]]
                (events)))

    (f 99)
    (is (match? [[:add "gnip.gnop" 99 clojure-domain-attributes]]
                (events)))))

(deftest valid-metric-names
  (are [expected input] (= expected (convert-metric-name input))

    "foo" :foo
    "foo.bar" :foo.bar
    "gnip/gnop" :gnip/gnop
    "io.pedestal.metrics/foo" ::metrics/foo

    "any old string" "any old string"

    "clojure.core/atom" `atom

    "unqualified-symbol" 'unqualified-symbol))

(deftest invalid-metric-name
  (when-let [e (is (thrown? ExceptionInfo
                            (convert-metric-name {})))]
    (is (= "Invalid metric name type: clojure.lang.PersistentArrayMap"
           (ex-message e)))
    (is (= {:metric-name {}})
        (ex-data e))))

(defn- extract-callback [events]
  (let [[_ _ callback] (->> events
                            (filter #(-> % first (= :buildWithCallback)))
                            first)]
    callback))

(deftest create-gauge
  (let [f                  (constantly 1547)
        _                  (metrics/gauge :example.gauge
                                          {::metrics/description "gauge description"
                                           ::metrics/unit        "yawns"
                                           :domain               "clojure"}
                                          f)
        setup-events       (events)
        _                  (is (match? [[:ofLongs "example.gauge"]
                                        [:setDescription "example.gauge" "gauge description"]
                                        [:setUnit "example.gauge" "yawns"]
                                        [:buildWithCallback "example.gauge" (m/pred some?)]]
                                       setup-events))
        ^Consumer callback (extract-callback setup-events)
        olm                (reify ObservableLongMeasurement
                             (record [_ value attributes]
                               (event :olm-record value attributes)))]

    ;; The callback is passed the OLM and its job is to call the function and report its value
    ;; to the OLM via its .record method.

    (.accept callback olm)
    (is (match? [[:olm-record 1547 clojure-domain-attributes]]
                (events)))))

(deftest create-duplicate-gauge
  (metrics/gauge "foo.bar.baz" {} (constantly -97))

  (is (match? [[:ofLongs "foo.bar.baz"]
               [:buildWithCallback "foo.bar.baz" (m/pred some?)]]
              (events)))

  (metrics/gauge "foo.bar.baz" {} (constantly 42))

  ;; Same name, attributes -> same gauge.
  ;; This is somewhat dicey as the reported value could be different and the new
  ;; value gets forgotten. But it's not clear what it means to build a duplicate gauge
  ;; inside open telemetry.

  (is (= []
         (events))))
