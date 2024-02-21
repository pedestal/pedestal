(ns io.pedestal.telemetry.otel-agent-init
  "Initialization of metrics and telemetry when the Otel agent is active."
  (:require [io.pedestal.metrics.otel :as otel])
  (:import (io.opentelemetry.api GlobalOpenTelemetry)))


;; TODO: This may work for both agent and no-agent, and maybe should be part of pedestal.metrics.

(defn metric-source
  []
  (-> (GlobalOpenTelemetry/getMeter "io.pedestal.metrics")
      (otel/wrap-meter)))

(defn tracing-source
  []
  (-> (GlobalOpenTelemetry/getTracer "io.pedestal.tracing")))
