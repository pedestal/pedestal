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

(defprotocol MetricSource

  "Provides methods to find or create counters and gauges."

  ;; TODO: Support tags

  (counter
    [source metric-name]
    "Finds or creates a counter metric with the given metric name.

    Returns a function used to increment the counter.  With no arguments,
    increments the counter by 1, or with a single numeric argument, increments
    the counter by that amount.")

  (gauge [source metric-name value-fn]
    "Creates, if necessary, a gauge with the given metric name.

    The value-fn will be periodically invoked and should return a long value.

    Returns nil."))
