; Copyright 2013 Relevance, Inc.
; Copyright 2014-2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.log
  "Logging via slf4j. Each logging level is a macro: trace, debug,
  info, warn, and error. Each namespace gets its own Logger. Arguments
  are key-value pairs, which will be printed as with 'pr'. The special
  key :exception should have a java.lang.Throwable as its value, and
  will be passed separately to the underlying logging API.
  One can override the logger via JVM or ENVAR settings."
  (:require clojure.string)
  (:import (org.slf4j Logger
                      LoggerFactory)
           (com.codahale.metrics MetricRegistry
                                 Gauge Counter Histogram Meter
                                 Slf4jReporter)
           (com.codahale.metrics.jmx JmxReporter)
           (io.opentracing Scope
                           Span
                           SpanContext
                           Tracer
                           Tracer$SpanBuilder)
           (io.opentracing.util GlobalTracer)
           (java.util Map)
           (java.util.concurrent TimeUnit)
           (clojure.lang IFn)))

(defprotocol LoggerSource
  (-level-enabled? [t level-key]
                   "Given the log level as a keyword,
                   return a boolean if that log level is currently enabled.")
  (-trace [t body]
          [t body throwable]
          "Log a TRACE message,
          and optionally handle a special Throwable/Exception related to the message.
          The body may be any of Clojure's literal data types, but a map or string is encouraged.")
  (-debug [t body]
          [t body throwable]
          "Log a DEBUG message,
          and optionally handle a special Throwable/Exception related to the message.
          The body may be any of Clojure's literal data types, but a map or string is encouraged.")
  (-info [t body]
         [t body throwable]
         "Log an INFO message,
         and optionally handle a special Throwable/Exception related to the message.
         The body may be any of Clojure's literal data types, but a map or string is encouraged.")
  (-warn [t body]
         [t body throwable]
         "Log a WARN message,
         and optionally handle a special Throwable/Exception related to the message.
         The body may be any of Clojure's literal data types, but a map or string is encouraged.")
  (-error [t body]
          [t body throwable]
          "Log an ERROR message,
          and optionally handle a special Throwable/Exception related to the message.
          The body may be any of Clojure's literal data types, but a map or string is encouraged."))

(extend-protocol LoggerSource
  Logger
  (-level-enabled? [t level-key]
    (case level-key
      :trace (.isTraceEnabled t)
      :debug (.isDebugEnabled t)
      :info (.isInfoEnabled t)
      :warn (.isWarnEnabled t)
      :error (.isErrorEnabled t)))
  (-trace
    ([t body]
     (.trace t ^String (if (string? body) body (pr-str body))))
    ([t body throwable]
     (.trace t (if (string? body) ^String body ^String (pr-str body)) ^Throwable throwable)))
  (-debug
    ([t body]
     (.debug t ^String (if (string? body) body (pr-str body))))
    ([t body throwable]
     (.debug t (if (string? body) ^String body ^String (pr-str body)) ^Throwable throwable)))
  (-info
    ([t body]
     (.info t ^String (if (string? body) body (pr-str body))))
    ([t body throwable]
     (.info t (if (string? body) ^String body ^String (pr-str body)) ^Throwable throwable)))
  (-warn
    ([t body]
     (.warn t ^String (if (string? body) body (pr-str body))))
    ([t body throwable]
     (.warn t (if (string? body) ^String body ^String (pr-str body)) ^Throwable throwable)))
  (-error
    ([t body]
     (.error t ^String (if (string? body) body (pr-str body))))
    ([t body throwable]
     (.error t (if (string? body) ^String body ^String (pr-str body)) ^Throwable throwable)))

  nil
  (-level-enabled? [t level-key] false)
  (-trace
    ([t body] nil)
    ([t body throwable] nil))
  (-debug
    ([t body] nil)
    ([t body throwable] nil))
  (-info
    ([t body] nil)
    ([t body throwable] nil))
  (-warn
    ([t body] nil)
    ([t body throwable] nil))
  (-error
    ([t body] nil)
    ([t body throwable] nil)))

;; Override the logger
;; ---------------------
;; Pedestal's logging is backed by a protocol, which you are free to extend
;; for your own system.
;; Per logging message, you can substitute in your own logger and bypass SLF4J,
;; using the :io.pedestal.log/logger key.
;; You can also override the logger for an entire application by setting the
;; JVM Property 'io.pedestal.log.overrideLogger' or ENVAR 'PEDESTAL_LOGGER'
;; to a symbol that resolves to a single-arity function
;; (passed a string logger tag, the NS string of the log call).
;; This function should return something that satisifes the LoggerSource protocol.
;; The function will be called multiple times (as the logging macros are expanded).

(defn- log-expr [form level keyvals]
  ;; Pull out :exception, otherwise preserve order
  (let [keyvals-map (apply array-map keyvals)
        exception' (:exception keyvals-map)
        logger' (gensym "logger")  ; for nested syntax-quote
        string' (gensym "string")
        log-method' (symbol (str "io.pedestal.log/-" (name level)))
        formatter (:io.pedestal.log/formatter keyvals-map pr-str)
        override-logger (some-> (or (System/getProperty "io.pedestal.log.overrideLogger")
                                    (System/getenv "PEDESTAL_LOGGER"))
                                symbol)]
    `(let [~logger' ~(or (::logger keyvals-map)
                         (and override-logger `(~override-logger ~(name (ns-name *ns*))))
                         `(LoggerFactory/getLogger ~(name (ns-name *ns*))))]
       (when (io.pedestal.log/-level-enabled? ~logger' ~level)
         (let [~string' (binding [*print-length* 80]
                          (~formatter ~(assoc (dissoc keyvals-map
                                                 :exception
                                                 :io.pedestal.log/logger
                                                 :io.pedestal.log/formatter)
                                         :line (:line (meta form)))))]
           ~(if exception'
              `(~log-method' ~logger'
                             ~(with-meta string'
                                         {:tag 'java.lang.String})
                             ~(with-meta exception'
                                         {:tag 'java.lang.Throwable}))
              `(~log-method' ~logger' ~string')))))))

(defmacro trace [& keyvals] (log-expr &form :trace keyvals))

(defmacro debug [& keyvals] (log-expr &form :debug keyvals))

(defmacro info [& keyvals] (log-expr &form :info keyvals))

(defmacro warn [& keyvals] (log-expr &form :warn keyvals))

(defmacro error [& keyvals] (log-expr &form :error keyvals))

(defmacro spy
  "Logs expr and its value at DEBUG level, returns value."
  [expr]
  (let [value' (gensym "value")]
    `(let [~value' ~expr]
       ~(log-expr &form :debug (vector :spy (list 'quote expr)
                                       :value value'))
       ~value')))

;; Utility/Auxiliary log functions
;; --------------------------------

(defn maybe-init-java-util-log
  "Invoke this once when starting your application to redirect all
  java.util.logging log messages to SLF4J. The current project's
  dependencies must include org.slf4j/jul-to-slf4j."
  []
  ;; Use reflection to avoid compile-time dependency on
  ;; org.slf4j/jul-to-slf4j
  (when-let [bridge (try (.. Thread currentThread getContextClassLoader
                             (loadClass "org.slf4j.bridge.SLF4JBridgeHandler"))
                         (catch Throwable t
                           nil))]
    (.. ^Class bridge
        (getMethod "removeHandlersForRootLogger" (make-array Class 0))
        (invoke nil (make-array Object 0)))
    (.. ^Class bridge
        (getMethod "install" (make-array Class 0))
        (invoke nil (make-array Object 0)))))

;; Metrics
;; -----------

(defprotocol MetricRecorder

  (-counter [t metric-name delta]
            "Update a single Numeric/Long metric by the `delta` amount")
  (-gauge [t metric-name value-fn]
          "Register a single metric value, returned by a 0-arg function;
          This function will be called everytime the Guage value is requested.")
  (-histogram [t metric-name value]
              "Measure a distribution of Long values")
  (-meter [t metric-name n-events]
          "Measure the rate of a ticking metric - a meter."))

(extend-protocol MetricRecorder

  MetricRegistry
  (-counter [registry metric-name delta]
    (when-let [c (.counter registry ^String metric-name)]
      (.inc ^Counter c delta)
      delta))

  (-gauge [registry metric-name value-fn]
    (try
      (.register registry ^String metric-name (reify Gauge
                                        (getValue [this] (value-fn))))
      value-fn
      (catch IllegalArgumentException iae
        nil)))

  (-histogram [registry metric-name value]
    (when-let [h (.histogram registry ^String metric-name)]
      (.update ^Histogram h ^long value)
      value))

  (-meter [registry metric-name n-events]
    (when-let [m (.meter registry ^String metric-name)]
      (.mark ^Meter m n-events)
      n-events))

  ;; One should reify the protocol to achieve this case
  ;; This may come back if it proves to be a common case to funnel/smuggle metrics
  ;clojure.lang.Fn
  ;(-counter [f metric-name delta]
  ;  (f :counter metric-name delta))
  ;(-gauge [f metric-name value-fn]
  ;  (f :gauge metric-name (value-fn)))
  ;(-histogram [f metric-name value]
  ;  (f :histogram metric-name value))
  ;(-meter [f metric-name n-events]
  ;  (f :meter metric-name n-events))

  nil
  (-counter [t m d]
    nil)
  (-gauge [t m vfn]
    nil)
  (-histogram [t m v]
    nil)
  (-meter [t m v]
    nil))

;; Utility/Auxiliary metric functions
;; ----------------------------------

(defn metric-registry
  "Create a metric-registry.
  Optionally pass in single-arg functions, which when passed a registry,
  create, start, and return a reporter."
  [& reporter-init-fns]
  (let [registry (MetricRegistry.)]
    (doseq [reporter-fn reporter-init-fns]
      (reporter-fn registry))
    registry))

(defn jmx-reporter [^MetricRegistry registry]
  (doto (some-> (JmxReporter/forRegistry registry)
                (.inDomain "io.pedestal.metrics")
                (.build))
    (.start)))

(defn log-reporter [^MetricRegistry registry]
  (doto (some-> (Slf4jReporter/forRegistry registry)
                (.outputTo (LoggerFactory/getLogger "io.pedestal.metrics"))
                (.convertRatesTo TimeUnit/SECONDS)
                (.convertDurationsTo TimeUnit/MILLISECONDS)
                (.build))
    (.start 1 TimeUnit/MINUTES)))

(def default-recorder
  "This is the default recorder of all metrics.
  This value is configured by setting the JVM Property 'io.pedestal.log.defaultMetricsRecorder'
  or the environment variable 'PEDESTAL_METRICS_RECORDER'.
  The value of the setting should be a namespaced symbol
  that resolves to a 0-arity function or nil.
  That function should return something that satisfies the MetricRecorder protocol.
  If no function is found, metrics will be reported only to JMX via a DropWizard MetricRegistry."
  (if-let [ns-fn-str (or (System/getProperty "io.pedestal.log.defaultMetricsRecorder")
                         (System/getenv "PEDESTAL_METRICS_RECORDER"))]
    (if (= "nil" ns-fn-str)
      nil
      ((resolve (symbol ns-fn-str))))
    (metric-registry jmx-reporter)))

;; Public Metrics API
;; -------------------

(defn format-name
  "Format a given metric name, regardless of type, into a string"
  [n]
  (if (keyword? n)
    (subs (str n) 1) ;; This preserves the namespace
    (str n)))

(defn counter
  ([metric-name ^Long delta]
   (-counter default-recorder (format-name metric-name) delta))
  ([recorder metric-name ^Long delta]
   (-counter recorder (format-name metric-name) delta)))

(defn gauge
  ([metric-name ^IFn value-fn]
   (-gauge default-recorder (format-name metric-name) value-fn))
  ([recorder metric-name ^IFn value-fn]
   (-gauge recorder (format-name metric-name) value-fn)))

(defn histogram
  ([metric-name ^Long value]
   (-histogram default-recorder (format-name metric-name) value))
  ([recorder metric-name ^Long value]
   (-histogram recorder (format-name metric-name) value)))

(defn meter
  ([metric-name]
   (-meter default-recorder (format-name metric-name) 1))
  ([metric-name ^Long n-events]
   (-meter default-recorder (format-name metric-name) n-events))
  ([recorder metric-name ^Long n-events]
   (-meter recorder (format-name metric-name) n-events)))

;; Tracing
;; -----------

(defprotocol TraceSpan
  (-set-operation-name [t operation-name]
                       "Given a span and the operation name (String),
                       set the logical operation this span represents,
                       and return the Span.")
  (-log-span [t msg]
             [t msg micros]
             "Given a span, a log message/string, and optionally an explicit timestamp in microseconds,
             Record the event to the span,
             and return the span.
             If no timestamp is specified, `now`/nanoTime is used, adjusted for microseconds.")
  (-tag-span [t tag-key tag-value]
             "Given a span, a tag key (String), and a tag value (String),
             Set the tag key-value pair on the span for recording,
             and returns the Span.

             Some trace systems support numeric, object, boolean and other values.
             The protocol encourages at a minimum String keys and values,
             but extensions of the protocols are free to make platform-specific type/arg optimizations.
             Some Trace platforms have semantics around tag keys/values, eg. https://github.com/opentracing/specification/blob/master/semantic_conventions.md")
  (-finish-span [t]
                [t micros]
                "Given a span,
                finish/complete and record the span optionally setting an explicit end timestamp in microseconds,
                and return the span.
                If no timestamp is specified, `now`/nanoTime is used, adjusted for microseconds.
                Multiple calls to -finishSpan should be noops"))

(defprotocol TraceSpanMap
  (-log-span-map [t msg-map]
                 [t msg-map micros]
                 "Given a span, a map of fields, and optionally an explicit timestamp in microseconds,
                 Record the event to the span,
                 and return the span.
                 Some Trace Recorders don't fully support round-tripping maps -- use carefully.
                 Some Trace platforms have semantics around key/values, eg. https://github.com/opentracing/specification/blob/master/semantic_conventions.md"))

(defprotocol TraceSpanBaggage
  (-set-baggage [t k v]
                "Given a span, a baggage key (String) and baggage value (String),
                add the key and value to the Span (and any additional context holding the span).
                and return the Span

                Adding baggage allows keys/values to be smuggled across span boundaries,
                creating a powerful distributed context.
                Baggage is only propagated to children of the span.")
  (-get-baggage [t k]
                [t k not-found]
                "Given a span, a baggage key, and optionally a `not-found` value,
                return the baggage value (String) for the corresponding key (if present).
                If the key isn't present, return `not-found` or nil.")
  (-get-baggage-map [t]
                    "Given a span,
                    return a Map of all baggage items."))

(defprotocol TraceOrigin
  (-register [t]
             "Given a Tracer/TraceOrigin
             perform whatver steps are necessary to register that Tracer/TraceOrigin
             to support the creation of spans,
             and return the Tracer/TraceOrigin.

             It should not be necessary to make this call in application code.
             This call is only used when bootstrapping Pedestal's `default-tracer`")
  (-span [t operation-name]
         [t operation-name parent]
         [t operation-name parent initial-tags]
         "Given a Tracer/TraceOrigin, an operation name,
         and optionally a parent Span, and optionally a map of initial tags
         return a new Span with the operation name set.
         If the parent is not set, the span has no parent (ie: current active spans are ignored).

         ** The span may be started on creation but should not be activated **
         This should be left to application-specific span builders.")
  (-activate-span [t span]
                  "Given a Tracer/TraceOrigin and a span,
                  activate the span
                  and return the newly activated span.")
  (-active-span [t]
                "Given a Tracer/TraceOrigin,
                return the current, active Span or nil if there isn't an active span"))

(extend-protocol TraceSpan
  nil
  (-set-operation-name [t operation-name] nil)
  (-log-span
    ([t msg] nil)
    ([t msg micros] nil))
  (-tag-span [t tag-key tag-value] nil)
  (-finish-span
    ([t] nil)
    ([t micros] nil))

  Span
  (-set-operation-name [t operation-name]
    (.setOperationName t (format-name operation-name))
    t)
  (-log-span
    ([t msg]
     (.log t ^String msg)
     t)
    ([t msg micros]
     (.log t ^long micros ^String micros)
     t))
  (-tag-span [t tag-key tag-value]
    (cond
      (string? tag-value) (.setTag t ^String (format-name tag-key) ^String tag-value)
      (number? tag-value) (.setTag t ^String (format-name tag-key) ^Number tag-value)
      (instance? Boolean tag-value) (.setTag t ^String (format-name tag-key) ^Boolean tag-value)
      :else (.setTag t ^String (format-name tag-key) ^String (str tag-value)))
    t)
  (-finish-span
    ([t] (.finish t) t)
    ([t micros] (.finish t micros) t))

  Scope
  (-set-operation-name [t operation-name]
    (-set-operation-name (.span t) operation-name))
  (-log-span
    ([t msg]
     (-log-span (.span t) msg))
    ([t msg micros]
     (-log-span (.span t) msg micros)))
  (-tag-span [t tag-key tag-value]
    (-tag-span (.span t) tag-key tag-value))
  (-finish-span
    ([t]
     (.close t)
     (-finish-span (.span t)))
    ([t micros]
     (.close t)
     (-finish-span (.span t) micros))))

(extend-protocol TraceSpanMap
  nil
  (-log-span-map
    ([t msg-map] nil)
    ([t msg-map micros] nil))

  Span
  (-log-span-map
    ([t msg-map]
     (.log t ^Map (persistent!
                    (reduce-kv (fn [acc k v]
                                 (conj! acc (format-name k) v))
                               (transient {})
                               msg-map)))
     t)
    ([t msg-map micros]
     (.log t ^Map (persistent!
                    (reduce-kv (fn [acc k v]
                                 (conj! acc (format-name k) v))
                               (transient {})
                               msg-map))
           micros)
     t))

  Scope
  (-log-span-map
    ([t msg-map]
     (-log-span-map (.span t) msg-map))
    ([t msg-map micros]
     (-log-span-map (.span t) msg-map micros))))

(extend-protocol TraceSpanBaggage
  nil
  (-set-baggage [t k v] nil)
  (-get-baggage
    ([t k] nil)
    ([t k not-found] not-found))
  (-get-baggage-map [t] {})

  Span
  (-set-baggage [t k v]
    (.setBaggageItem t (format-name k) (str v))
    t)
  (-get-baggage
    ([t k]
     (.getBaggageItem t (format-name k)))
    ([t k not-found]
     (or (.getBaggageItem t (format-name k)) not-found)))
  (-get-baggage-map [t]
    (into {} (.baggageItems ^SpanContext (.context t))))

  Scope
  (-set-baggage [t k v]
    (-set-baggage (.span t) k v))
  (-get-baggage
    ([t k] (-get-baggage (.span t) k))
    ([t k not-found] (-get-baggage (.span t) k not-found)))
  (-get-baggage-map [t] (-get-baggage-map (.span t))))

(extend-protocol TraceOrigin
  nil
  (-register [t] nil)
  (-span
    ([t operation-name] nil)
    ([t opertaion-name parent] nil))
  (-activate-span [t span] nil)
  (-active-span [t] nil)

  Tracer
  (-register [t]
    (GlobalTracer/register t))
  (-span
    ([t operation-name]
     (.start ^Tracer$SpanBuilder (.ignoreActiveSpan (.buildSpan t (format-name operation-name)))))
    ([t operation-name parent]
     (let [builder (.buildSpan t (format-name operation-name))
           builder (if (instance? Span parent)
                     (.asChildOf builder ^Span parent)
                     (.asChildOf builder ^SpanContext parent))]
       (.start ^Tracer$SpanBuilder builder)))
    ([t operation-name parent initial-tags]
     (let [builder (.buildSpan t (format-name operation-name))
           builder (cond
                     (nil? parent) (.ignoreActiveSpan builder)
                     (instance? Span parent) (.asChildOf builder ^Span parent)
                     :else (.asChildOf builder ^SpanContext parent))]
       (reduce (fn [builder k v]
                 (cond
                   (string? v) (.withTag builder ^String (format-name k) ^String v)
                   (number? v) (.withTag builder ^String (format-name k) ^Number v)
                   (instance? Boolean v) (.withTag ^String (format-name k) ^Boolean v)
                   :else (.withTag ^String (format-name k) ^String (str v))))
               builder
               initial-tags)
       (.start ^Tracer$SpanBuilder builder))))
  (-activate-span [t span]
    (.activate (.scopeManager t) ^Span span false))
  (-active-span [t]
    (if-let [scope (.active (.scopeManager t))]
      (.span ^Scope scope)
      nil)))

;; Utility/Auxiliary trace functions
;; ----------------------------------

(def default-tracer
  "This is the default Tracer, registered as the OpenTracing's GlobalTracer.
  This value is configured by setting the JVM Property 'io.pedestal.log.defaultTracer'
  or the environment variable 'PEDESTAL_TRACER'.
  The value of the setting should be a namespaced symbol
  that resolves to a 0-arity function or nil.
  That function should return something that satisfies the TracerOrigin protocol.
  If no function is found, the GlobalTracer will default to the NoopTracer and `GlobalTracer/isRegistered` will be false."
  (if-let [ns-fn-str (or (System/getProperty "io.pedestal.log.defaultTracer")
                         (System/getenv "PEDESTAL_TRACER"))]
    (if (= "nil" ns-fn-str)
      nil
      (let [tracer ((resolve (symbol ns-fn-str)))]
        (when-not (GlobalTracer/isRegistered)
          (-register tracer))
        tracer))
    (GlobalTracer/get)))

;; Public Tracing API
;; -------------------

(defn span
  "Given an operation name,
  and optionally a parent Span, and optionally a map of initial tags
  return a new Span with the operation name set, started, and active.

  If the parent is not set, the span has no parent (ie: current active spans are ignored)."
  ([operation-name]
   (-activate-span default-tracer
                   (-span default-tracer operation-name)))
  ([operation-name parent-span]
   (-activate-span default-tracer
                   (-span default-tracer operation-name parent-span)))
  ([operation-name parent-span initial-tags]
   (-activate-span default-tracer
                   (-span default-tracer operation-name parent-span initial-tags))))

(defn active-span
  "Return the current active span;
  Returns nil if there isn't an active span."
  []
  (-active-span default-tracer))

(defn tag-span
  ([span m]
   (reduce-kv (fn [span' k v]
                (-tag-span span' k v))
              span
              m))
  ([span k v]
   (-tag-span span k v))
  ([span tag-k tag-v & kvs]
   (assert (even? (count kvs)) (str "You're trying to tag a span with an uneven set of key/value pairs.
                                    Perhaps this key is missing a value: " (last kvs)
                                    "\nProblem pair seq: " (pr-str kvs)))
   (reduce (fn [span' [k v]]
             (-tag-span span' k v))
           (-tag-span span tag-k tag-v)
           (partition 2 kvs))))

(defn log-span
  ([span x]
   (cond
     (string? x) (-log-span span x)
     (map? x) (-log-span-map span x)
     :else (-log-span span (format-name x))))
  ([span k v]
   (-log-span-map span {k v}))
  ([span k v & kvs]
   (assert (even? (count kvs)) (str "You're trying to log to a span with an uneven set of key/value pairs.
                                    Perhaps this key is missing a value: " (last kvs)
                                    "\nProblem pair seq: " (pr-str kvs)))
   (-log-span-map span (assoc (apply hash-map kvs) k v))))

(defn span-baggage
  ([span]
   (-get-baggage-map span))
  ([span k]
   (-get-baggage span k))
  ([span k not-found]
   (-get-baggage span k not-found)))

(defn add-span-baggage!
  ([span m]
   (reduce-kv (fn [span' k v]
                (-set-baggage span' k v))
              span
              m))
  ([span k v]
   (-set-baggage span k v))
  ([span bag-k bag-v & kvs]
   (assert (even? (count kvs)) (str "You're trying to set span baggage with an uneven set of key/value pairs.
                                    Perhaps this key is missing a value: " (last kvs)
                                    "\nProblem pair seq: " (pr-str kvs)))
   (reduce (fn [span' [k v]]
             (-set-baggage span' k v))
           (-set-baggage span bag-k bag-v)
           (partition 2 kvs))))

(defn finish-span
  [span]
  (-finish-span span))

