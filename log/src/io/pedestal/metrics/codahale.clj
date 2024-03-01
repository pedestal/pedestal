; Copyright 2023 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.metrics.codahale
  "Extends the [[MetricSource]] SPI to the Codahale metrics library supplied by DropWizard.

   Note that Codahale metrics do not have an equivalent concept to tags, so any tags
   are ignored.

   This exists to make it possible to continue using Codahale metrics (the standard through Pedestal 0.6) with Pedestal 0.7."
  {:since      "0.7.0"
   :deprecated "0.7.0"}
  (:require [io.pedestal.metrics.spi :as spi])
  (:import (com.codahale.metrics Counter Gauge MetricRegistry Timer)
           (io.pedestal.metrics.spi MetricSource)))

(defprotocol MetricRegistrySource

  (metric-registry ^MetricRegistry [source]
    "Returns the underlying MetricRegistry from a MetricSource.

    This is primarily intended for tests."))

(defn- prepare-metric-name [n]
  (cond
    (string? n) n
    (keyword? n) (subs (str n) 1)
    (symbol? n) (str n)

    :else
    (throw (ex-info (str "Invalid metric name: " n)
                    {:metric-name n}))))
(defn- create-counter
  [^MetricRegistry registry metric-name]
  (let [counter (.counter registry (prepare-metric-name metric-name))]
    (fn
      ([] (.inc counter))
      ([n] (.inc counter (long n))))))

(defn- create-gauge
  [^MetricRegistry registry metric-name value-fn]
  (let [gauge (reify Gauge
                (getValue [_]
                  (value-fn)))]
    (.register registry (prepare-metric-name metric-name) gauge)
    nil))

(defn- create-timer
  [^MetricRegistry registry metric-name]
  (let [timer (.timer registry (prepare-metric-name metric-name))]
    (fn start-timer []
      (let [context (.time timer)
            *first? (atom true)]
        (fn stop-timer []
          ;; Only the first call to the stop timer fn does anything, extra calls are ignored.
          ;; Needed? This is more than the underlying API does!
          (when (compare-and-set! *first? true false)
            (.stop context)))))))

(defn- write-to-cache
  [*cache k v]
  (swap! *cache assoc k v)
  v)


(defn wrap-registry
  ^MetricSource [^MetricRegistry registry]
  (let [*counters (atom {})
        *gauges   (atom #{})
        *timers   (atom {})]
    (reify

      MetricRegistrySource
      (metric-registry [_] registry)

      spi/MetricSource

      (counter [_ metric-name _tags]
        (or (get @*counters metric-name)
            (write-to-cache *counters metric-name (create-counter registry metric-name))))

      (gauge [_ metric-name _tags value-fn]
        (when-not (contains? @*gauges metric-name)
          (create-gauge registry metric-name value-fn)
          (swap! *gauges conj metric-name))
        nil)

      (timer [_ metric-name _tags]
        (or (get @*timers metric-name)
            (write-to-cache *timers metric-name (create-timer registry metric-name)))))))

(defn get-counter
  ^Counter [source metric-name]
  (.counter (metric-registry source) (prepare-metric-name metric-name)))

(defn get-gauge
  ^Gauge [source metric-name]
  (.gauge (metric-registry source) (prepare-metric-name metric-name)))

(defn get-timer
  ^Timer [source metric-name]
  (.timer (metric-registry source) (prepare-metric-name metric-name)))

(defn default-registry
  ^MetricRegistry []
  (MetricRegistry.))
