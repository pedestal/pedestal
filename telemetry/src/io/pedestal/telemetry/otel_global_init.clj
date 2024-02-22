; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.telemetry.otel-global-init
  "Uses GlobalOpenTelemetry to provide defaults for metrics source and tracing source."
  {:added "0.7.0"}
  (:require [io.pedestal.metrics.otel :as otel])
  (:import (io.opentelemetry.api GlobalOpenTelemetry)))

(defn metric-source
  []
  (-> (GlobalOpenTelemetry/getMeter "io.pedestal.metrics")
      (otel/wrap-meter)))

(defn tracing-source
  []
  (-> (GlobalOpenTelemetry/getTracer "io.pedestal.tracing")))

