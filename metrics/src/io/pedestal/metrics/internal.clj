; Copyright 2023 NuBank NA

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
  (:require [clojure.string :as string]))


(defn create-default-metric-source
  []
  (let [config-value (or (System/getProperty "io.pedestal.metrics.metric-source")
                         (System/getenv "PEDESTAL_METRICS_SOURCE")
                         "io.pedestal.metrics.micrometer/default-source")
        [ns-str symbol-str] (string/split config-value #"/")
        symbol-name  (symbol ns-str symbol-str)
        v            (or (requiring-resolve symbol-name)
                         (throw (ex-info (str "Unable to create default metric source; no such var: " symbol-name)
                                         {:symbol-name symbol-name})))]
    (try
      (v)
      (catch Exception e
        (throw (ex-info (format "Error invoking function %s (to create default metric source): %s"
                                config-value
                                (ex-message e))
                        {:symbol-name symbol-name}
                        e))))))
