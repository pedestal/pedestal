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
  (:import (io.micrometer.core.instrument Counter Counter$Builder Gauge Meter$Id Meter$Type MeterRegistry Metrics Tags)
           (io.micrometer.core.instrument.simple SimpleMeterRegistry)
           (java.util.function Supplier)))

(def ^SimpleMeterRegistry default-registry (SimpleMeterRegistry.))

(Metrics/addRegistry default-registry)

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

(defn add-registry
  "Adds a new registry to the global registry."
  [^MeterRegistry registry]
  (Metrics/addRegistry registry))

(defn set-registry
  "Replaces the default SimpleMeterRegistry with the provided registry."
  [^MeterRegistry new-registry]
  (Metrics/addRegistry new-registry)
  (Metrics/removeRegistry default-registry))

(defn- new-counter
  [^MeterRegistry registry ^String metric-name]
  (let [counter (-> (Counter/builder (prepare-name metric-name))
                    ;; TODO: Tags
                    (.register registry))]
    (fn
      ([]
       (.increment counter))
      ([amount]
       (.increment (double amount))))))

(defn- new-gauge
  [^MeterRegistry registry ^String metric-name value-fn]
  (let [supplier (reify Supplier
                   (get [_] (value-fn)))]
    (-> (Gauge/builder (prepare-name metric-name) supplier)
        ;; TODO: Tags
        (.register registry))))

(defn- write-to-cache
  [*cache k value]
  (swap! *cache assoc k value)
  value)

(defn wrap-registry
  "Wraps the registry as a [[MetricSource]]."
  [^MeterRegistry registry]
  ;; Can't have meters with same name but different type. This caught on metric creation.
  ;; We use separate caches though, otherwise we could mistakenly return a gauge instead
  ;; of a (function wrapped around a) counter.
  (let [*counters (atom {})
        *gauges   (atom {})]
    (reify spi/MetricSource

      (counter [_ metric-name]
        (or (get-in @*counters metric-name)
            (write-to-cache *counters metric-name
                            (new-counter registry metric-name))))

      (gauge [_ metric-name value-fn]
        (when-not (contains? @*gauges metric-name)
          (write-to-cache *gauges metric-name (new-gauge registry metric-name value-fn)))
        nil))))

(defn default-source
  []
  (wrap-registry (SimpleMeterRegistry.)))
