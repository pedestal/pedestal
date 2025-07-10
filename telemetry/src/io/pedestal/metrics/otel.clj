; Copyright 2024-2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.metrics.otel
  "Default metrics implementation based on OpenTelemetry."
  {:added "0.7.0"}
  (:require [io.pedestal.metrics.spi :as spi]
            [io.pedestal.telemetry.internal :as i])
  (:import (io.opentelemetry.api.common Attributes)
           (io.opentelemetry.api.metrics DoubleGaugeBuilder DoubleHistogramBuilder LongCounterBuilder
                                         LongHistogram Meter ObservableDoubleMeasurement ObservableLongMeasurement)
           (java.util.function Consumer)))

(defn- convert-metric-name
  [metric-name]
  (or
    (i/to-str metric-name)
    (throw (ex-info (str "Invalid metric name type: " (-> metric-name class .getName))
                    {:metric-name metric-name}))))

(defn- map->Attributes
  ^Attributes [attributes]
  (i/map->Attributes (when attributes
                       (dissoc attributes
                               :io.pedestal.metrics/unit
                               :io.pedestal.metrics/description
                               :io.pedestal.metrics/value-type))))

(defn- use-doubles?
  [attributes]
  (= :double (get attributes :io.pedestal.metrics/value-type spi/metric-value-type)))

(defn- new-counter
  [^Meter meter metric-name attributes]
  (let [{:io.pedestal.metrics/keys [description unit]} attributes
        as-doubles? (use-doubles? attributes)
        ^LongCounterBuilder builder'
                    (-> (.counterBuilder meter (convert-metric-name metric-name))
                        (cond->
                          description (.setDescription description)
                          unit (.setUnit unit)))
        attributes' (map->Attributes attributes)]
    (if as-doubles?
      (let [counter (-> builder' .ofDoubles .build)]
        (fn
          ([]
           (.add counter 1.0 attributes'))
          ([^double increment]
           (.add counter increment attributes'))))
      (let [counter (.build builder')]
        (fn
          ([]
           (.add counter 1 attributes'))
          ([^long increment]
           (.add counter increment attributes')))))))

(defn- new-histogram
  [^Meter meter metric-name attributes]
  (let [{:io.pedestal.metrics/keys [description unit]} attributes
        as-longs?   (not (use-doubles? attributes))
        ^DoubleHistogramBuilder builder
                    (-> (.histogramBuilder meter (convert-metric-name metric-name))
                        (cond->
                          description (.setDescription description)
                          unit (.setUnit unit)))
        attributes' (map->Attributes attributes)]
    (if as-longs?
      (let [histogram (-> builder .ofLongs .build)]
        (fn [^long value]
          (.record ^LongHistogram histogram value attributes')))
      (let [histogram (.build builder)]
        (fn [^double value]
          (.record histogram value attributes'))))))

(defn- new-gauge
  [^Meter meter metric-name attributes value-fn]
  (let [{:io.pedestal.metrics/keys [description unit]} attributes
        attributes' (map->Attributes attributes)
        as-longs?   (not (use-doubles? attributes))
        callback    (if as-longs?
                      (reify Consumer
                        (accept [_ measurement]
                          (.record ^ObservableLongMeasurement measurement (value-fn) attributes')))
                      (reify Consumer
                        (accept [_ measurement]
                          (.record ^ObservableDoubleMeasurement measurement (value-fn) attributes'))))
        ^DoubleGaugeBuilder builder
                    (-> (.gaugeBuilder meter (convert-metric-name metric-name))
                        (cond->
                          description (.setDescription description)
                          unit (.setUnit unit)))]
    (if as-longs?
      (-> builder .ofLongs (.buildWithCallback callback))
      (.buildWithCallback builder callback))))

;; Future work:
;; Tag to configure granularity of the timer
;; Tag to choose histogram vs. counter as underlying data

(defn- new-timer
  [^Meter meter metric-name attributes time-source-fn]
  (let [counter-fn (new-counter meter metric-name attributes)
        as-longs?  (not (use-doubles? attributes))]
    (fn start-timer []
      (let [start-nanos (time-source-fn)
            *first?     (atom true)
            prep-nanos  (if as-longs?
                          (fn [^long value]
                            ;; nanos -> millis as a long
                            (Math/floorDiv value 1000000))
                          (fn [^long value]
                            (quot value 1000000)))]

        (fn stop-timer []
          ;; Only the first call to the stop timer fn does anything,
          ;; extra calls are ignored.
          (let [elapsed-nanos (- (time-source-fn) start-nanos)]
            (when (compare-and-set! *first? true false)
              ;; Pass the number of milliseconds to the counter.
              ;; Yes this means we lose fractional amounts of milliseconds.
              (counter-fn (prep-nanos elapsed-nanos)))))))))


(defn- default-time-source
  ^long []
  (System/nanoTime))

(defn- write-to-cache
  [*cache k value]
  (swap! *cache assoc k value)
  value)

(defn wrap-meter
  "Wraps a Meter instance as a [[MetricSource]].

  time-source-fn: used for testing, returns the current time in nanoseconds; used with timers."
  ([^Meter meter]
   (wrap-meter meter default-time-source))
  ([^Meter meter time-source-fn]
   (assert (some? meter))
   ;; Can't have meters with same name but different type. This is caught on metric creation.
   ;; We use separate caches though, otherwise we could mistakenly return a gauge instead
   ;; of a (function wrapped around a) counter, etc.
   (let [*counters   (atom {})
         *gauges     (atom {})
         *histograms (atom {})
         *timers     (atom {})]
     (reify spi/MetricSource

       (counter [_ metric-name attributes]
         (let [k [metric-name attributes]]
           (or (get @*counters k)
               (write-to-cache *counters k (new-counter meter metric-name attributes)))))

       (gauge [_ metric-name attributes value-fn]
         (let [k [metric-name attributes]]
           (when-not (contains? @*gauges k)
             (write-to-cache *gauges k
                             (new-gauge meter metric-name attributes value-fn))))
         nil)

       (histogram [_ metric-name attributes]
         (let [k [metric-name attributes]]
           (or (get @*histograms k)
               (write-to-cache *histograms k (new-histogram meter metric-name attributes)))))

       (timer [_ metric-name attributes]
         (let [k [metric-name attributes]]
           (or (get @*timers k)
               (write-to-cache *timers k (new-timer meter metric-name attributes time-source-fn)))))))))

