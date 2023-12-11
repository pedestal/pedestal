; Copyright 2023 NuBank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.metrics.spi
  "Service Provider Interface for metrics providers; protocols that providers should expose and implement."
  {:since "0.7.0"})

(defprotocol GaugeMetric [])
(defprotocol CounterMetric [])
(defprotocol HistogramMetric [])
(defprotocol MeterMetric [])

(defprotocol MetricsProvider

  (counter ^CounterMetric [provider metric-name delta]
    "Advance a single numeric metric by the delta amount.")

  (gauge ^GaugeMetric [provider metric-name value-fn]
    "Registers a single metric value ....")

  (histogram ^HistogramMetric [provider metric-name value]
    "Used to provide values that are combined into a histogram metric.")

  (meter ^MeterMetric [provider metric-name n-events]
    "Measure the rate of a ticking metric."))
