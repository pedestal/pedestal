; Copyright 2023-2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.metrics
  "Metrics functionality, built on the metrics SPI (service provider interface)."
  {:added "0.7.0"}
  (:require [io.pedestal.metrics.spi :as spi]
            [io.pedestal.telemetry.internal :as internal]))

(def ^:dynamic *default-metric-source*
  "The default metric source, used when a specific metric source is not specified.

  This may itself be nil, in which case default no-op behavior will occur."
  (internal/create-default-metric-source))

(defn counter
  "Finds or creates a counter metric with the given metric name.

  Returns a function used to increment the counter.  Invoked with no arguments,
  increments the counter by 1, or with a single numeric argument, increments
  the counter by that amount."
  ([metric-name attributes]
   (counter *default-metric-source* metric-name attributes))
  ([metric-source metric-name attributes]
   (spi/counter metric-source metric-name attributes)))

(defn increment-counter
  "Increments a counter metric by 1.

  Returns nil."
  ([metric-name attributes]
   (increment-counter *default-metric-source* metric-name attributes))
  ([metric-source metric-name attributes]
   ;; Obtain and invoke the counter's increment function
   ((spi/counter metric-source metric-name attributes))
   nil))

(defn advance-counter
  "Increments a counter metric by a numeric amount (which should be positive).

  Returns nil."
  ([metric-name attributes amount]
   (advance-counter *default-metric-source* metric-name attributes amount))
  ([metric-source metric-name attributes amount]
   ((spi/counter metric-source metric-name attributes) amount)
   nil))

(defn gauge
  "Creates a gauge that obtains its metric values from value-fn, which must return
   a number.  Does nothing if a gauge with that name already exists.

   Returns nil."
  ([metric-name attributes value-fn]
   (gauge *default-metric-source* metric-name attributes value-fn))
  ([metric-source metric-name attributes value-fn]
   (spi/gauge metric-source metric-name attributes value-fn)))

(defn timer
  "Creates a timer and return the timer's trigger function.
  Invoking the trigger starts tracking execution duration, and returns another function that stops
  the timer and records the elapsed duration.

  The stop timer function is idempotent; only the first call records a duration.

  Internally, timers measure elapsed nanosecond time."
  ([metric-name attributes]
   (timer *default-metric-source* metric-name attributes))
  ([metric-source metric-name attributes]
   (spi/timer metric-source metric-name attributes)))

(defmacro timed*
  "Variant of [[timed]] when using a specific metric source."
  [metric-source metric-name attributes & body]
  `(let [stop-fn# ((timer ~metric-source ~metric-name ~attributes))]
     (try
       (do ~@body)
       (finally
         (stop-fn#)))))

(defmacro timed
  "Obtains and starts a timer, then executes the body adding a (try ... finally) block to stop
   the timer, using  the [[*default-metric-source*]]."
  [metric-name attributes & body]
  `(timed* *default-metric-source* ~metric-name ~attributes ~@body))

(defn histogram
  "Creates a histogram (sometimes called a distribution summary), which tracks the number of events and a dimension for each event;
  internally, distributes different events to various bucket ranges, yielding a histogram of sizes of
  the event; a comment example is to use a histogram to track the size of incoming requests
  or outgoing responses.

  Returns a function that records the dimension of an event, optionally with per-event attributes."
  ([metric-name attributes]
   (histogram *default-metric-source* metric-name attributes))
  ([metric-source metric-name attributes]
   (spi/histogram metric-source metric-name attributes)))




