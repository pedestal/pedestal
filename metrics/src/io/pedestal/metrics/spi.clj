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

  "Provides methods to find or create counters and gauges.

  Metrics are created using a metric-name (which can be a string, keyword, or symbol)
  and tags (which may be nil).  Tags are sometimes referred to as dimensions, and may
  qualify the metric (for example, a request counter may have tags to identify the URL
  of the request).

  Tags are converted internally into maps of string keys to string values; for keywords,
  the leading `:` is stripped off.  Tag keys may be strings, keywords or symbols;
  Tag values are the same, but may also be numbers or booleans.

  Metric names are required, but tags may be nil.
  "

  (counter [source metric-name tags]
    "Finds or creates a counter metric with the given metric name.

    Returns a function used to increment the counter.  Invoked with no arguments,
    increments the counter by 1, or with a single numeric argument, increments
    the counter by that amount.")

  (gauge [source metric-name tags value-fn]
    "Creates, if necessary, a gauge with the given metric name.

    The value-fn will be periodically invoked and should return a long value.

    If called when the gauge already exists, returns without doing anything.

    Returns nil.")

  (timer [source metric-name tags]
    "Finds or creates a timer, returning the timer's trigger function.

    When invoked, the trigger function starts a timer and returns a
    new function that stops the timer."))
