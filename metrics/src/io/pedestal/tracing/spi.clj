; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.tracing.spi
  "Defines the TracingSource protocol, and provides implementations on nil nad on Tracer."
  (:require [io.pedestal.telemetry.internal :as i])
  (:import (io.opentelemetry.api OpenTelemetry)
           (io.opentelemetry.api.trace SpanBuilder Tracer)))

(defprotocol TracingSource

  (create-span
    ^SpanBuilder [this operation-name attributes]
    "Creates a new SpanBuilder, from which a Span can be created; additional functions
    in io.pedestal.telemetry allow the span to be configured prior to being activated."))

(extend-protocol TracingSource

  nil

  (create-span [_ _ _]
    (-> (OpenTelemetry/noop)
        (.getTracer "noop")
        (.spanBuilder "noop span")))

  Tracer

  (create-span [tracer operation-name attributes]
    (-> (.spanBuilder tracer (i/to-str operation-name))
        (.setAllAttributes (i/map->Attributes attributes {:value-fn i/to-str})))))
