; Copyright 2024-2025 Nubank NA

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
            [io.pedestal.metrics.spi :as spi]
            [matcher-combinators.matchers :as m]
            [io.pedestal.metrics.otel :as otel]
            [io.pedestal.telemetry.internal :refer [convert-key]]
            [clojure.test :refer [deftest is are use-fixtures]])
  (:import (clojure.lang ExceptionInfo)
           (io.opentelemetry.api.metrics DoubleCounter DoubleCounterBuilder DoubleGaugeBuilder DoubleHistogram DoubleHistogramBuilder LongCounter LongCounterBuilder LongGaugeBuilder LongHistogram LongHistogramBuilder Meter
                                         ObservableDoubleGauge ObservableDoubleMeasurement ObservableLongGauge ObservableLongMeasurement)
           (java.util.function Consumer)))

(def *now (atom 0))

(def *events (atom nil))

(defn- events
  "Returns value of **events before clearing it."
  []
  (let [result @*events]
    (reset! *events [])
    result))

(defn- event
  [& args]
  (swap! *events i/vec-conj (vec args))
  nil)

(defn mock-double-counter-builder
  [name]
  (reify

    DoubleCounterBuilder

    (setDescription [this description]
      (event :setDescription name description)
      this)

    (setUnit [this unit]
      (event :setUnit name unit)
      this)

    (build [this]
      (event :build-double-counter name)
      this)

    DoubleCounter

    (add [_ value]
      (event :add-double name value))

    (add [_ value attributes]
      (event :add-double name value attributes))))

(defn mock-long-counter-builder
  [name]
  (reify

    LongCounterBuilder

    (setDescription [this description]
      (event :setDescription name description)
      this)

    (setUnit [this unit]
      (event :setUnit name unit)
      this)

    (ofDoubles [_]
      (event :ofDoubles name)
      (mock-double-counter-builder name))

    (build [this]
      (event :build-long-counter name)
      this)

    LongCounter

    (add [_ value]
      (event :add-long name value))

    (add [_ value attributes]
      (event :add-long name value attributes))))

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
      (event :buildWithCallback :long name callback)
      this)

    ObservableLongGauge))

(defn mock-double-gauge-builder
  [name]
  (reify
    DoubleGaugeBuilder

    (setDescription [this description]
      (event :setDescription name description)
      this)

    (setUnit [this unit]
      (event :setUnit name unit)
      this)

    (ofLongs [_]
      (event :ofLongs name)
      (mock-long-gauge-builder name))

    (buildWithCallback [this callback]
      (event :buildWithCallback :double name callback)
      this)

    ObservableDoubleGauge))

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
      (event :build-long name)
      this)

    LongHistogram

    (record [_ value attributes]
      (event :record-long name value attributes))))


(defn mock-double-histogram-builder
  [name]
  (reify DoubleHistogramBuilder

    (setDescription [this description]
      (event :setDescription name description)
      this)

    (setUnit [this unit]
      (event :setUnit name unit)
      this)

    (build [this]
      (event :build-double name)
      this)

    (ofLongs [_]
      (event :ofLongs name)
      (mock-long-histogram-builder name))

    DoubleHistogram

    (record [_ value attributes]
      (event :record-double name value attributes))))

(def mock-meter
  (reify

    Meter

    (counterBuilder [_ name]
      (mock-long-counter-builder name))

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
    (is (match? [[:build-long-counter "foo.bar.baz"]
                 [:build-long-counter "foo.bar.baz"]]
                (events)))

    (f1)

    (is (match? [[:add-long "foo.bar.baz" 1 empty-attributes]]
                (events)))

    (f2 5)

    (is (match? [[:add-long "foo.bar.baz" 5 empty-attributes]]
                (events)))))

(deftest counter-with-description-and-unit
  (let [metric-name :gnip.gnop
        f           (metrics/counter metric-name
                                     {::metrics/description "description"
                                      ::metrics/unit        "unit"
                                      :domain               "clojure"})]
    (is (match? [[:setDescription "gnip.gnop" "description"]
                 [:setUnit "gnip.gnop" "unit"]
                 [:build-long-counter "gnip.gnop"]]
                (events)))

    (f 99)
    (is (match? [[:add-long "gnip.gnop" 99 clojure-domain-attributes]]
                (events)))))

(deftest increment-counter
  (let [metric-name :test.counter
        attributes  {:domain "clojure"}]
    (metrics/increment-counter metric-name attributes)

    (is (match? [[:build-long-counter "test.counter"]
                 [:add-long "test.counter" 1 clojure-domain-attributes]]
                (events)))


    (metrics/increment-counter metric-name attributes)

    (is (match? [[:add-long "test.counter" 1 clojure-domain-attributes]]
                (events)))))

(deftest increment-double-counter
  (let [metric-name :test.counter
        attributes  {:domain              "clojure"
                     ::metrics/value-type :double}]

    (metrics/increment-counter metric-name attributes)

    (is (match? [[:ofDoubles "test.counter"]
                 [:build-double-counter "test.counter"]
                 [:add-double "test.counter" 1.0 clojure-domain-attributes]]
                (events)))

    (metrics/increment-counter metric-name attributes)

    (is (match? [[:add-double "test.counter" 1.0 clojure-domain-attributes]]
                (events)))))

(deftest advance-counter
  (let [metric-name :test.counter
        attributes  {:domain "clojure"}]
    (metrics/advance-counter metric-name attributes 7)

    (is (match? [[:build-long-counter "test.counter"]
                 [:add-long "test.counter" 7 clojure-domain-attributes]]
                (events)))


    (metrics/advance-counter metric-name attributes 9)

    (is (match? [[:add-long "test.counter" 9 clojure-domain-attributes]]
                (events)))))

(deftest advance-double-counter
  (let [metric-name :test.counter
        attributes  {:domain              "clojure"
                     ::metrics/value-type :double}]
    (metrics/advance-counter metric-name attributes 7)

    (is (match? [[:ofDoubles "test.counter"]
                 [:build-double-counter "test.counter"]
                 [:add-double "test.counter" 7.0 clojure-domain-attributes]]
                (events)))


    (metrics/advance-counter metric-name attributes 9)

    (is (match? [[:add-double "test.counter" 9.0 clojure-domain-attributes]]
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
    (is (= {:key {37 :long}}
           (ex-data e)))))

(defn- extract-callback [events]
  (let [[_ _ _ callback] (->> events
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
        _                  (is (match? [[:setDescription "example.gauge" "gauge description"]
                                        [:setUnit "example.gauge" "yawns"]
                                        [:ofLongs "example.gauge"]
                                        [:buildWithCallback :long "example.gauge" (m/pred some?)]]
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

(deftest create-double-gauge
  (let [f                  (constantly 1547)
        _                  (metrics/gauge :example.gauge
                                          {::metrics/description "gauge description"
                                           ::metrics/unit        "yawns"
                                           ::metrics/value-type :double
                                           :domain               "clojure"}
                                          f)
        setup-events       (events)
        _                  (is (match? [[:setDescription "example.gauge" "gauge description"]
                                        [:setUnit "example.gauge" "yawns"]
                                        [:buildWithCallback :double "example.gauge" (m/pred some?)]]
                                       setup-events))
        ^Consumer callback (extract-callback setup-events)
        olm                (reify ObservableDoubleMeasurement
                             (record [_ value attributes]
                               (event :olm-record value attributes)))]

    ;; The callback is passed the OLM and its job is to call the function and report its value
    ;; to the OLM via its .record method.

    (.accept callback olm)
    (is (match? [[:olm-record 1547.0 clojure-domain-attributes]]
                (events)))))

(deftest create-duplicate-gauge
  (metrics/gauge "foo.bar.baz" {} (constantly -97))

  (is (match? [[:ofLongs "foo.bar.baz"]
               [:buildWithCallback :long "foo.bar.baz" (m/pred some?)]]
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
        _          (is (match? [[:build-long-counter "timer.test"]]
                               (events)))
        _          (reset! *now (* 50 nanosecond))
        stop-fn    (timer-fn)]

    (swap! *now + (* elapsed-ms 1000 1000))

    (is (match? []
                (events)))

    (stop-fn)

    (is (match? [[:add-long "timer.test" elapsed-ms empty-attributes]]
                (events)))

    ;; A second call to stop-fn does nothing

    (stop-fn)

    (is (match? []
                (events)))))

(deftest create-double-timer
  (let [elapsed-ms 60
        timer-fn   (metrics/timer :timer.test {::metrics/value-type :double})
        _          (is (match? [[:ofDoubles "timer.test"]
                                [:build-double-counter "timer.test"]]
                               (events)))
        _          (reset! *now (* 50 nanosecond))
        stop-fn    (timer-fn)]

    (swap! *now + (* elapsed-ms 1000 1000))

    (is (match? []
                (events)))

    (stop-fn)

    (is (match? [[:add-double "timer.test" (double elapsed-ms) empty-attributes]]
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
    (is (match? [[:build-long-counter "timed.macro"]
                 [:add-long "timed.macro" elapsed-ms clojure-domain-attributes]]
                (events)))))

(deftest histogram
  (let [update-fn (metrics/histogram :histogram.test {:domain               "clojure"
                                                      ::metrics/description "histogram description"
                                                      ::metrics/unit        "laughs"})]
    (is (match? [[:setDescription "histogram.test" "histogram description"]
                 [:setUnit "histogram.test" "laughs"]
                 [:ofLongs "histogram.test"]
                 [:build-long "histogram.test"]]
                (events)))

    (update-fn 42)

    (is (match? [[:record-long "histogram.test" 42 clojure-domain-attributes]]
                (events)))))

(deftest double-histogram
  (let [update-fn (metrics/histogram :histogram.test {:domain               "clojure"
                                                      ::metrics/description "histogram description"
                                                      ::metrics/unit        "laughs"
                                                      ::metrics/value-type :double})]
    (is (match? [[:setDescription "histogram.test" "histogram description"]
                 [:setUnit "histogram.test" "laughs"]
                 [:build-double "histogram.test"]]
                (events)))

    (update-fn 42)

    (is (match? [[:record-double "histogram.test" 42.0 clojure-domain-attributes]]
                (events)))))


(deftest counter-with-nil-source-is-noop
  (is (nil?
        ((spi/counter nil nil nil)))))

(deftest gauge-with-nil-source-is-nil
  (is (nil?
        (spi/gauge nil nil nil nil))))

(deftest histogram-with-nil-source-is-noop
  (is (nil?
        ((spi/histogram nil nil nil)))))

(deftest timer-with-nil-source-is-noop
  (let [start-fn (spi/timer nil nil nil)
        stop-fn  (start-fn)]
    (is (nil? (stop-fn)))))
