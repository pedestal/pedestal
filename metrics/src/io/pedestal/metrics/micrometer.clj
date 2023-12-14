; Copyright 2023 NuBank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.metrics.micrometer
  "Default metrics implementation based on Micrometer."
  {:since "0.7.0"}
  (:require [io.pedestal.metrics.spi :as spi])
  (:import (io.micrometer.core.instrument Counter Gauge Meter$Builder MeterRegistry Metrics)
           (io.micrometer.core.instrument.simple SimpleMeterRegistry)
           (java.util.function Supplier)))

(defn- prepare-name
  [metric-name]
  (cond
    (string? metric-name)
    metric-name

    (keyword? metric-name)
    (subs (str metric-name) 1)

    (symbol? metric-name)
    (str metric-name)

    :else
    (throw (ex-info (str "Invalid metric name: " metric-name)
                    {:metric-name metric-name}))))

#_
(defn add-registry
  "Adds a new registry to the global registry."
  [^MeterRegistry registry]
  (Metrics/addRegistry registry))

#_
(defn set-registry
  "Replaces the default SimpleMeterRegistry with the provided registry."
  [^MeterRegistry new-registry]
  (Metrics/addRegistry new-registry)
  (Metrics/removeRegistry default-registry))

(defn- convert-key
  [k]
  (cond
    (string? k) k
    (keyword? k) (subs (str k) 1)
    (symbol? k) (str k)
    ;; TODO: Maybe support Class?

    :else
    (throw (ex-info "Invalid Tag key type: " (-> k class .getName)
                    {:key k}))))

(defn- convert-value
  [v]
  (cond
    (string? v) v
    (keyword? v) (subs (str v) 1)
    (symbol? v) (str v)
    (number? v) (str v)

    :else
    (throw (ex-info "Invalid Tag value type: " (-> v class .getName)
                    {:value v}))))

(defn- add-tags
  ^Meter$Builder [^Meter$Builder builder metric-name tags]
  (when (seq tags)
    (try
      (doseq [[k v] tags]
        (.tag builder (convert-key k) (convert-value v)))
      (catch Exception e
        (throw (ex-info (format "Exception building tags for metric %s: %s"
                                metric-name
                                (ex-message e))
                        {:metric-name metric-name
                         :tags        tags}
                        e)))))
  builder)

(defn- new-counter
  [^MeterRegistry registry ^String metric-name tags]
  (let [^Counter counter (-> (Counter/builder (prepare-name metric-name))
                             (add-tags metric-name tags)
                             (.register registry))]
    (fn
      ([]
       (.increment counter))
      ([amount]
       (.increment counter (double amount))))))

(defn- new-gauge
  [^MeterRegistry registry ^String metric-name tags value-fn]
  (let [supplier (reify Supplier
                   (get [_] (value-fn)))]
    (-> (Gauge/builder (prepare-name metric-name) supplier)
        (add-tags metric-name tags)
        (.register registry))))

(defn- write-to-cache
  [*cache k value]
  (swap! *cache assoc k value)
  value)

(defn wrap-registry
  "Wraps the registry as a [[MetricSource]]."
  [^MeterRegistry registry]
  ;; Can't have meters with same name but different type. This is caught on metric creation.
  ;; We use separate caches though, otherwise we could mistakenly return a gauge instead
  ;; of a (function wrapped around a) counter.
  (let [*counters (atom {})
        *gauges   (atom {})]
    (reify spi/MetricSource

      (counter [_ metric-name tags]
        (let [k [metric-name tags]]
          (or (get-in @*counters k)
              (write-to-cache *counters k
                              (new-counter registry metric-name tags)))))

      (gauge [_ metric-name tags value-fn]
        (let [k [metric-name tags]]
          (when-not (contains? @*gauges k)
            (write-to-cache *gauges k (new-gauge registry metric-name value-fn))))
        nil))))

(defn default-source
  []
  ;; TODO: Support for common tags
  (wrap-registry (SimpleMeterRegistry.)))
