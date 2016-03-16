; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

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
  will be passed separately to the underlying logging API."
  (:require clojure.string)
  (:import (org.slf4j LoggerFactory)
           (com.codahale.metrics MetricRegistry
                                 Gauge Counter Histogram Meter
                                 JmxReporter Slf4jReporter)
           (java.util.concurrent TimeUnit)
           (clojure.lang IFn)))

(defn- log-expr [form level keyvals]
  ;; Pull out :exception, otherwise preserve order
  (let [exception' (:exception (apply array-map keyvals))
        keyvals' (mapcat identity (remove #(= :exception (first %))
                                          (partition 2 keyvals)))
        logger' (gensym "logger")  ; for nested syntax-quote
        string' (gensym "string")
        enabled-method' (symbol (str ".is"
                                     (clojure.string/capitalize (name level))
                                     "Enabled"))
        log-method' (symbol (str "." (name level)))]
    `(let [~logger' (LoggerFactory/getLogger ~(name (ns-name *ns*)))]
       (when (~enabled-method' ~logger')
         (let [~string' (binding [*print-length* 80]
                          (pr-str (array-map :line ~(:line (meta form)) ~@keyvals')))]
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
       ~(log-expr &form 'debug (vector :spy (list 'quote expr)
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
    (.. bridge
        (getMethod "removeHandlersForRootLogger" (make-array Class 0))
        (invoke nil (make-array Object 0)))
    (.. bridge
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

(def default-recorder (metric-registry jmx-reporter))

;; Public Metrics API
;; -------------------

(defn counter
  ([metric-name ^Long delta]
   (-counter default-recorder (str metric-name) delta))
  ([recorder metric-name ^Long delta]
   (-counter recorder (str metric-name) delta)))

(defn gauge
  ([metric-name ^IFn value-fn]
   (-gauge default-recorder (str metric-name) value-fn))
  ([recorder metric-name ^IFn value-fn]
   (-gauge recorder (str metric-name) value-fn)))

(defn histogram
  ([metric-name ^Long value]
   (-histogram default-recorder (str metric-name) value))
  ([recorder metric-name ^Long value]
   (-histogram recorder (str metric-name) value)))

(defn meter
  ([metric-name]
   (-meter default-recorder (str metric-name) 1))
  ([metric-name ^Long n-events]
   (-meter default-recorder (str metric-name) n-events))
  ([recorder metric-name ^Long n-events]
   (-meter recorder (str metric-name) n-events)))

