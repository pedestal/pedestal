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
  (:import (io.opentelemetry.api GlobalOpenTelemetry)
           (io.opentelemetry.api.trace SpanBuilder)))

(defprotocol TracingSource

  (create-span
    ^SpanBuilder [this operation-name attributes]
    "Creates a new SpanBuilder, from which a Span can be created; additional functions
    in io.pedestal.telemetry allow the span to be configured prior to being activated."))

(extend-type nil
  TracingSource

  (create-span [_ _ _]
    (-> (GlobalOpenTelemetry/get)
        (.getTracer "noop")
        (.spanBuilder "noop span"))))
