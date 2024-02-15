; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.tracing
  "Wrappers around Open Telemetry tracing."
  {:added "0.7.0"}
  (:require [io.pedestal.telemetry.internal :as i]
            [io.pedestal.tracing.spi :as spi])
  (:import (io.opentelemetry.api.trace Span SpanBuilder SpanKind)))

(def ^:dynamic *tracing-source*
  (i/create-default-tracing-source))

(defn create-span
  "Creates a new span builder, wrapped in a map, which allows configuration of the span prior to starting it."
  ([operation-name attributes]
   (create-span *tracing-source* operation-name attributes))
  ([tracing-source operation-name attributes]
   {::builder (spi/create-span tracing-source operation-name attributes)}))

(def ^:private span-kinds
  {:internal SpanKind/INTERNAL
   :server   SpanKind/SERVER
   :client   SpanKind/CLIENT
   :producer SpanKind/PRODUCER
   :consumer SpanKind/CONSUMER})

(defn with-kind
  "Labels the span with a kind (:internal, :server, :client, :producer, or :consumer)."
  [span-map kind]
  (update span-map ::builder #(.setSpanKind ^SpanBuilder % (get span-kinds kind))))

(defn as-root
  "Identifies the new span as a root span, with no parent.  When this is not called, and span is active
  in the Open Telemetry context, the active span will be the parent of the new span when the span is started."
  [span-map]
  (update span-map ::builder #(.setNoParent ^SpanBuilder %)))

(defn start
  "Builds the span from the span map, starting and returning it."
  ^Span [span-map]
  (let [{::keys [^SpanBuilder builder]} span-map]
    (.startSpan builder)))

(defn add-attribute
  "Adds an attribute to a span, typically, to record response attributes such as the status code.
  This should not be called after the span has ended."
  ^Span [^Span span attribute-key attribute-value]
  (.setAttribute span (i/convert-key attribute-key) attribute-value))

(defn end-span
  [^Span span]
  (.end span))

