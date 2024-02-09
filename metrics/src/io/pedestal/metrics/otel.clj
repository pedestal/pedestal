; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.metrics.otel
  "Default metrics implementatation based on OpenTelemetry."
  {:since "0.7.0"}
  (:require [io.pedestal.metrics.spi :as spi])
  (:import (io.opentelemetry.api.common AttributeKey Attributes AttributesBuilder)
           (io.opentelemetry.api.metrics Meter ObservableLongMeasurement)
           (java.util.function Consumer)))

(defn- convert-metric-name
  [metric-name]
  (cond
    (string? metric-name)
    metric-name

    (keyword? metric-name)
    (subs (str metric-name) 1)

    (symbol? metric-name)
    (str metric-name)

    :else
    (throw (ex-info (str "Invalid metric name type: " (-> metric-name class .getName))
                    {:metric-name metric-name}))))

(defn- convert-key
  ^AttributeKey [k]
  (let [s (cond
            (string? k) k
            (keyword? k) (subs (str k) 1)
            (symbol? k) (str k)
            ;; TODO: Maybe support Class?

            :else
            (throw (ex-info (str "Invalid Tag key type: " (-> k class .getName))
                            {:key k})))]
    (AttributeKey/stringKey s)))

(defn- tags->attributes
  ^Attributes [tags]
  (let [tags' (dissoc tags :io.pedestal.metrics/unit :io.pedestal.metrics/description)]
    (if-not (seq tags')
      (Attributes/empty)
      (->> (reduce-kv (fn [^AttributesBuilder b k v]
                        (.put b (convert-key k) v))
                      (Attributes/builder)
                      tags')
           .build))))

(defn- new-counter
  [^Meter meter metric-name tags]
  (let [{:io.pedestal.metrics/keys [description unit]} tags
        counter    (-> (.counterBuilder meter (convert-metric-name metric-name))
                       (cond->
                         description (.setDescription description)
                         unit (.setUnit unit))
                       .build)
        attributes (tags->attributes tags)]
    (fn
      ([]
       (.add counter 1 attributes))
      ([increment]
       (.add counter increment attributes)))))

(defn- new-gauge
  [^Meter meter metric-name tags value-fn]
  (let [{:io.pedestal.metrics/keys [description unit]} tags
        attributes (tags->attributes tags)
        callback   (reify Consumer
                     (accept [_ measurement]
                       (.record ^ObservableLongMeasurement measurement (value-fn) attributes)))]
    (-> (.gaugeBuilder meter (convert-metric-name metric-name))
        .ofLongs
        (cond->
          description (.setDescription description)
          unit (.setUnit unit))
        (.buildWithCallback callback))))

;; Future work:
;; Tag to configure granularity of the timer
;; Tag to choose histogram vs. counter as underlying data

(defn- new-timer
  [^Meter meter metric-name tags time-source-fn]
  (let [counter-fn (new-counter meter metric-name tags)]
    (fn start-timer []
      (let [start-nanos (time-source-fn)
            *first?     (atom true)]
        (fn stop-timer []
          ;; Only the first call to the stop timer fn does anything,
          ;; extra calls are ignored.
          (let [elapsed-nanos (- (time-source-fn) start-nanos)]
            (when (compare-and-set! *first? true false)
              ;; Pass the number of milliseconds to the counter.
              ;; Yes this means we lose fractional amounts of milliseconds.
              (counter-fn
                (Math/floorDiv elapsed-nanos 1000000)))))))))

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
   (let [*counters (atom {})
         *gauges   (atom {})
         *timers   (atom {})]
     (reify spi/MetricSource

       (counter [_ metric-name tags]
         (let [k [metric-name tags]]
           (or (get @*counters k)
               (write-to-cache *counters k (new-counter meter metric-name tags)))))

       (gauge [_ metric-name tags value-fn]
         (let [k [metric-name tags]]
           (when-not (contains? @*gauges k)
             (write-to-cache *gauges k
                             (new-gauge meter metric-name tags value-fn))))
         nil)

       (timer [_ metric-name tags]
         (let [k [metric-name tags]]
           (or (get @*timers k)
               (write-to-cache *timers k (new-timer meter metric-name tags time-source-fn)))))))))
