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
  "Metrics on SPI (service provide interface)."
  {:since "0.7.0"}
  (:require [io.pedestal.metrics.spi :as spi]
            [io.pedestal.metrics.internal :as internal]))

(def ^:dynamic *default-metric-source*
  "The default metric source, used when a metric source is not specified.

  TODO: Describe props, envs, etcs."
  (internal/create-default-metric-source))

(defn counter
  "Gets or creates a counter.  Returns a counter function.

  Invoking the counter function with no arguments increments the counter by 1.
  Invoking it with a numeric argument increments it by that amount."
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
   ;; Obtain and invoke the function.
   ((spi/counter metric-source metric-name tags))
   nil))

(defn advance-counter
  "Increments a counter metric by a numeric amount.

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
   (spi/gauge metric-source metric-name tags value-fn)
   nil))






