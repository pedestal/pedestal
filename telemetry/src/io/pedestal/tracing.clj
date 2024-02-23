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
  (:import (io.opentelemetry.api.common AttributeKey)
           (io.opentelemetry.api.trace Span SpanBuilder SpanKind StatusCode)
           (io.opentelemetry.context Context)))

(def ^:dynamic *tracing-source*
  (i/create-default-tracing-source))

(def ^:dynamic *context*
  "The active OpenTelemetry context used as the parent of any created spans.  If nil,
  then the span is created with the OpenTelemetry's default current context.

  This is designed to be bound when a span is created so that new spans created
  subsequently in other threads (typically, asynchronous execution) can connect to the appropriate
  context. In those cases, OpenTelemetry's current context is not always accurate, as it is stored
  in a thread-local variable."
  nil)

(defn create-span
  "Creates a new span builder, which allows configuration of the span prior to starting it."
  (^SpanBuilder [operation-name attributes]
   (create-span *tracing-source* operation-name attributes))
  (^SpanBuilder [tracing-source operation-name attributes]
   (cond-> (spi/create-span tracing-source operation-name attributes)
     *context* (.setParent *context*))))

(def ^:private span-kinds
  {:internal SpanKind/INTERNAL
   :server   SpanKind/SERVER
   :client   SpanKind/CLIENT
   :producer SpanKind/PRODUCER
   :consumer SpanKind/CONSUMER})

(defn with-kind
  "Updates the span builder to label the new span with a kind (:internal, :server, :client, :producer, or :consumer)."
  ^SpanBuilder [^SpanBuilder builder kind]
  (.setSpanKind builder (get span-kinds kind)))

(defn as-root
  "Identifies the new span as a root span, with no parent.  When this is not called, and span is active
  in the Open Telemetry context, the active span will be the parent of the new span when the span is started."
  ^SpanBuilder [^SpanBuilder builder]
  (.setNoParent builder))

(defn start
  "Builds the span from the span builder, starting and returning it."
  ^Span [^SpanBuilder builder]
  (.startSpan builder))

;; Could add extra functions to allow setting the start time

(defn add-attribute
  "Adds an attribute to a span, typically, to record response attributes such as the status code.
  This should not be called after the span has ended."
  ^Span [^Span span attribute-key attribute-value]
  (let [[k v] (i/kv->pair attribute-key attribute-value)]
    (.setAttribute span ^AttributeKey k v)))

(defn end-span
  "Ends the span, which will set its termination time to current time.  Every started span
  must be ended.

  Returns nil."
  [^Span span]
  (.end span))

(defn ^Span rename-span
  ^Span [^Span span span-name]
  (.updateName span span-name))

(def ^:private status-codes
  {:ok    StatusCode/OK
   :error StatusCode/ERROR
   :unset StatusCode/UNSET})

(defn set-status-code
  "Set the status code of the span to either :ok, :error, or :unset."
  ^Span [^Span span status-code]
  (.setStatus span (get status-codes status-code)))

(defn record-exception
  ^Span [^Span span ^Exception e]
  (.recordException span e))

(defn make-span-context
  "Creates a Context with the current context and the provided span."
  ^Context [^Span span]
  (.with (Context/current) span))

(defn make-context-current
  "Makes the context the current context, returning a no-args function to close the scope (restoring the prior
  current scope)."
  [^Context context]
  (let [scope (.makeCurrent context)]
    (fn close-scope []
      (.close scope))))
