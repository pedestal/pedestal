(ns org.example.sse.telemetry-init
  (:require [io.pedestal.metrics.otel :as otel])
  (:import (io.opentelemetry.api GlobalOpenTelemetry)))

(defn metric-source
  "Wraps the meter obtained from GlobalOpenTelemetry, with an
  instrumentation scope name of org.example.sse.metrics."
  []
  (-> (GlobalOpenTelemetry/getMeter "org.example.sse.metrics")
      (otel/wrap-meter)))

(defn tracing-source
  "Returns the tracer obtained from GlobalOpenTelementry, with an
  instrumentation scope name of org.example.sse.tracing."
  []
  (GlobalOpenTelemetry/getTracer "org.example.sse.tracing"))
