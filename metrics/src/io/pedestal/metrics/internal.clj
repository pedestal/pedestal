; Copyright 2023 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:no-doc io.pedestal.metrics.internal
  "Internal utils subject to change without notice."
  {:since "0.7.0"}
  (:require [io.pedestal.internal :as i]))

(defn create-default-metric-source
  []
  (let [v (i/resolve-var-from "io.pedestal.metrics.metric-source"
                              "PEDESTAL_METRICS_SOURCE"
                              "io.pedestal.metrics.otel/default-source")]
    (try
      (v)
      (catch Exception e
        (throw (RuntimeException. (format "Error invoking function %s (to create default metric source)"
                                          (str v))
                                  e))))))
