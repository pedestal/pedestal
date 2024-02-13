(ns io.pedestal.metrics.otel-init
  (:require [io.pedestal.metrics.otel :as otel])
  (:import (io.opentelemetry.sdk.autoconfigure AutoConfiguredOpenTelemetrySdk)))

(defn default-source
  "Default source for the global metric source."
  []
  (let [open-telemetry-sdk (-> (AutoConfiguredOpenTelemetrySdk/initialize)
                               (.getOpenTelemetrySdk))
        meter              (-> (.meterBuilder open-telemetry-sdk "io.pedestal.metrics")
                               (.setInstrumentationVersion "1.0.0")
                               .build)]
    (otel/wrap-meter meter)))
