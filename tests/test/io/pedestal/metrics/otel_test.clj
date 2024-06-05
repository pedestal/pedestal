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
            [io.pedestal.telemetry.internal :refer [convert-key]]
            [clojure.test :refer [deftest is are use-fixtures]])
  (:import (clojure.lang ExceptionInfo)
           (io.opentelemetry.api.metrics DoubleGaugeBuilder DoubleHistogramBuilder LongCounter LongCounterBuilder LongGaugeBuilder LongHistogram LongHistogramBuilder Meter
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

(defn mock-long-histogram-builder
  [name]
  (reify LongHistogramBuilder

    (setDescription [this description]
      (event :setDescription name description)
      this)

    (setUnit [this unit]
      (event :setUnit name unit)
      this)

    (build [this]
      (event :build name)
      this)

    LongHistogram

    (record [_ value attributes]
      (event :record name value attributes))))

(defn mock-double-histogram-builder
  [name]
  (reify DoubleHistogramBuilder

    (ofLongs [_]
      (event :ofLongs name)
      (mock-long-histogram-builder name))))

(def mock-meter
  (reify

    Meter

    (counterBuilder [_ name]
      (mock-counter-builder name))

    (gaugeBuilder [_ name]
      (mock-double-gauge-builder name))

    (histogramBuilder [_ name]
      (mock-double-histogram-builder name))))

(defn mock-metric-fixture
  [f]
  (binding [metrics/*default-metric-source* (otel/wrap-meter mock-meter
                                                             (fn [] @*now))]
    (reset! *events [])
    (reset! *now 0)
    (f)))

(use-fixtures :each mock-metric-fixture)

(def convert-metric-name @#'otel/convert-metric-name)

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

(deftest increment-counter
  (let [metric-name :test.counter
        attributes        {:domain "clojure"}]
    (metrics/increment-counter metric-name attributes)

    (is (match? [[:build-counter "test.counter"]
                 [:add "test.counter" 1 clojure-domain-attributes]]
                (events)))


    (metrics/increment-counter metric-name attributes)

    (is (match? [[:add "test.counter" 1 clojure-domain-attributes]]
                (events)))))

(deftest advance-counter
  (let [metric-name :test.counter
        attributes        {:domain "clojure"}]
    (metrics/advance-counter metric-name attributes 7)

    (is (match? [[:build-counter "test.counter"]
                 [:add "test.counter" 7 clojure-domain-attributes]]
                (events)))


    (metrics/advance-counter metric-name attributes 9)

    (is (match? [[:add "test.counter" 9 clojure-domain-attributes]]
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

(deftest valid-keys
  (are [expected input] (= expected (convert-key input))

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
    (is (= {:metric-name {}}
           (ex-data e)))))

(deftest invalid-tag-key
  (when-let [e (is (thrown-with-msg? Exception #"\QInvalid attribute key type: clojure.lang.PersistentArrayMap\E"
                                     (convert-key {37 :long})))]
    (is (= {:key        {37 :long}}
           (ex-data e)))))

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

(def nanosecond 1000000000)
(deftest create-timer
  (let [elapsed-ms 60
        timer-fn   (metrics/timer :timer.test nil)
        _          (is (match? [[:build-counter "timer.test"]]
                               (events)))
        _          (reset! *now (* 50 nanosecond))
        stop-fn    (timer-fn)]

    (swap! *now + (* elapsed-ms 1000 1000))

    (is (match? []
                (events)))

    (stop-fn)

    (is (match? [[:add "timer.test" elapsed-ms empty-attributes]]
                (events)))

    ;; A second call to stop-fn does nothing

    (stop-fn)

    (is (match? []
                (events)))))

(deftest timed-macro
  (let [elapsed-ms 37]
    (reset! *now (* 8387 nanosecond))
    (metrics/timed :timed.macro {:domain "clojure"}
      (swap! *now + (* elapsed-ms 1000 1000)))
    (is (match? [[:build-counter "timed.macro"]
                 [:add "timed.macro" elapsed-ms clojure-domain-attributes]]
                (events)))))

(deftest histogram
  (let [update-fn (metrics/histogram :histogram.test {:domain "clojure"
                                                      ::metrics/description "histogram description"
                                                      ::metrics/unit "laughs"})]
      (is (match? [[:ofLongs "histogram.test"]
                   [:setDescription "histogram.test" "histogram description"]
                   [:setUnit "histogram.test" "laughs"]
                   [:build "histogram.test"]]
                  (events)))

      (update-fn 42)

      (is (match? [[:record "histogram.test" 42 clojure-domain-attributes]]
                   (events)))))

