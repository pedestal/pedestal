(ns io.pedestal.telemetry.otel-init
  (:require [io.pedestal.metrics.otel :as otel])
  (:import (io.opentelemetry.sdk.autoconfigure AutoConfiguredOpenTelemetrySdk)))

(def ^:private *sdk
  (delay (-> (AutoConfiguredOpenTelemetrySdk/initialize)
             (.getOpenTelemetrySdk))))

(defn default-metric-source
  "Default source for the global metric source."
  []
  (-> @*sdk
      (.meterBuilder "io.pedestal.metrics")
      (.setInstrumentationVersion "1.0.0")
      .build
      otel/wrap-meter))

(defn default-tracing-source
  []
  (-> @*sdk
      (.getTracerProvider)
      (.get "io.pedestal.tracing")))
