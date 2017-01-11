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
                                 JmxReporter Slf4jReporter)
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
        override-logger (some-> (or (System/getProperty "io.pedestal.log.overrideLogger")
                                    (System/getenv "PEDESTAL_LOGGER"))
                                symbol)]
    `(let [~logger' ~(or (::logger keyvals-map)
                         (and override-logger `(~override-logger ~(name (ns-name *ns*))))
                         `(LoggerFactory/getLogger ~(name (ns-name *ns*))))]
       (when (io.pedestal.log/-level-enabled? ~logger' ~level)
         (let [~string' (binding [*print-length* 80]
                          (pr-str (assoc (dissoc ~keyvals-map
                                                 :exception :io.pedestal.log/logger)
                                         :line ~(:line (meta form)))))]
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
    (subs (str n) 1)
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

