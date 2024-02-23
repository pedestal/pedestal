; Copyright 2023 Nubank NA

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

  "Provides methods to find or create counters, timers, and gauges.

  Metrics are created using a metric-name (which can be a string, keyword, or symbol)
  and attributes (which may be nil).  attributes are used to configure and qualify the metric
  (for example, a request counter may have attributes to identify the URL of the request).

  Attributes keys are converted to string; for keywords,
  the leading `:` is stripped off.

  Metric names are required, but attributes may be nil.

  Some implementations may use specific attributes (typically, with namespace qualified keyword keys)
  to configure the metric in some additional way. Such configuration attributes are stripped out
  of the attributes that are reported to the underlying metric consumer.

  The protocol is extended on nil with a no-op implementation of the methods."

  (counter [source metric-name attributes]
    "Finds or creates a counter metric with the given metric name.

    Counters should only ever increase.

    Returns a function used to increment the counter.  Invoked with no arguments,
    increments the counter by 1, or with a single numeric argument, increments
    the counter by that amount.")

  (gauge [source metric-name attributes value-fn]
    "Creates, if necessary, a gauge with the given metric name.

    The value-fn will be periodically invoked and should return a long value.

    If called when the gauge already exists, returns without doing anything.

    Returns nil.")

  (timer [source metric-name attributes]
    "Finds or creates a timer, returning the timer's trigger function.

    When invoked, the trigger function starts a timer and returns a
    new function that stops the timer.")

  (histogram [source metric-name attributes]
    "Finds or creates a distribution summary, returning a function.

    The function is passed a value, to record that value as a new event that will be
    included in the distribution summary."))

(extend-type nil

  MetricSource

  (counter [_ _ _] (constantly nil))

  (gauge [_ _ _ _] nil)

  (timer [_ _ _]
    (fn noop-start []
      (fn noop-end [])))

  (histogram [_ _ _] (constantly nil)))
