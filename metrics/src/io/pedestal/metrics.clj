; Copyright 2023 NuBank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.metrics
  "Metrics functionality, built on the metrics SPI (service provide interface)."
  {:since "0.7.0"}
  (:require [io.pedestal.metrics.spi :as spi]
            [io.pedestal.metrics.internal :as internal]))

(def ^:dynamic *default-metric-source*
  "The default metric source, used when a metric source is not specified.

  TODO: Describe props, envs, etcs."
  (internal/create-default-metric-source))

(defn counter
  "Finds or creates a counter metric with the given metric name.

  Returns a function used to increment the counter.  Invoked with no arguments,
  increments the counter by 1, or with a single numeric argument, increments
  the counter by that amount."
  ([metric-name tags]
   (counter *default-metric-source* metric-name tags))
  ([metric-source metric-name tags]
   (spi/counter metric-source metric-name tags)))

(defn increment-counter
  "Increments a counter metric by 1.

  Returns nil."
  ([metric-name tags]
   (increment-counter *default-metric-source* metric-name tags))
  ([metric-source metric-name tags]
   ;; Obtain and invoke the counter's increment function
   ((spi/counter metric-source metric-name tags))
   nil))

(defn advance-counter
  "Increments a counter metric by a numeric amount (which should be positive).

  Returns nil."
  ([metric-name tags amount]
   (advance-counter *default-metric-source* metric-name tags amount))
  ([metric-source metric-name tags amount]
   ((spi/counter metric-source metric-name tags) amount)
   nil))

(defn gauge
  "Creates a gauge that obtains its metric values from value-fn, which must return
   a number.  Does nothing if a gauge with that name already exists.

   Returns nil."
  ([metric-name tags value-fn]
   (gauge *default-metric-source* metric-name tags value-fn))
  ([metric-source metric-name tags value-fn]
   (spi/gauge metric-source metric-name tags value-fn)))

(defn timer
  "Creates a timer and return the timer's trigger function.
  Invoking the trigger starts tracking execution duration, and returns another function that stops
  the timer and records the elapsed duration.

  The stop timer function is idempotent; only the first call records a duration.

  Internally, timers measure elapsed nanosecond time."
  ([metric-name tags]
   (timer *default-metric-source* metric-name tags))
  ([metric-source metric-name tags]
   (spi/timer metric-source metric-name tags)))

(defmacro timed*
  "Variant of [[timed]] when using a specific metric source."
  [metric-source metric-name tags & body]
  `(let [stop-fn# ((timer ~metric-source ~metric-name ~tags))]
     (try
       (do ~@body)
       (finally
         (stop-fn#)))))

(defmacro timed
  "Obtains and starts a timer, then executes the body adding a (try ... finally) block to stop
   the timer, using  the [[*default-metric-source*]]."
  [metric-name tags & body]
  `(timed* *default-metric-source* ~metric-name ~tags ~@body))




