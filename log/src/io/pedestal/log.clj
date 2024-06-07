; Copyright 2021-2024 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2018 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.log
  "A logging wrapper around SLF4J (but adaptable to other logging systems).
  Primary macros are [[trace]], [[debug]], [[info]], [[warn]], and [[error]]."
  (:require [clojure.string :as string]
            [io.pedestal.internal :as i :refer [deprecated]])
  (:import (org.slf4j Logger
                      LoggerFactory
                      MDC)
           (org.slf4j.spi MDCAdapter)
           (java.util Map)))

(defprotocol LoggerSource

  "Adapts an underlying logger (such as defined by SLF4J) to io.pedestal.log.

   For -trace, -debug, etc., the body will typically be a String, formatted from
   the event map; if you write code that directly invokes these methods,
   but use the io.pedestal.log implementation of LoggerSource for SLF4J, then
   Strings will pass through unchanged, but other Clojure types will be converted to strings
   via `pr-str`.

   If you write your own LoggerSource, you understand the same requirements: io.pedestal.log's
   macros will only supply a String body, but other code may pass other types."

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

(defprotocol LoggingMDC

  (-get-mdc [t k]
            [t k not-found]
            "Given a String key and optionally a `not-found` value (which should be a String),
            lookup the key in the MDC and return the value (A String);
            Returns nil if the key isn't present, or `not-found` if value was supplied.")
  (-put-mdc [t k v]
            "Given a String key and a String value,
            Add an entry to the MDC,
            and return the MDC instance.

            If k is nil, the original MDC is returned.")
  (-remove-mdc [t k]
               "Given a String key,
               remove the key-value entry in the MDC if the key is present
               And return the MDC instance.")
  (-clear-mdc [t]
              "Remove all entries within the MDC
              and return the MDC instance.")
  (-set-mdc [t m]
            "Given a map (of String keys and String values),
            Copy all key-values from the map to the MDC
            and return the MDC instance."))

(defn- format-body
  ^String [body]
  (if (string? body)
    body
    (pr-str body)))

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
     (.trace t (format-body body)))
    ([t body throwable]
     (.trace t (format-body body) ^Throwable throwable)))
  (-debug
    ([t body]
     (.debug t (format-body body)))
    ([t body throwable]
     (.debug t (format-body body) ^Throwable throwable)))
  (-info
    ([t body]
     (.info t (format-body body)))
    ([t body throwable]
     (.info t (format-body body) ^Throwable throwable)))
  (-warn
    ([t body]
     (.warn t (format-body body)))
    ([t body throwable]
     (.warn t (format-body body) ^Throwable throwable)))
  (-error
    ([t body]
     (.error t (format-body body)))
    ([t body throwable]
     (.error t (format-body body) ^Throwable throwable)))

  nil
  (-level-enabled? [_ _level-key] false)
  (-trace
    ([_ _body] nil)
    ([_ _body _throwable] nil))
  (-debug
    ([_ _body] nil)
    ([_ _body _throwable] nil))
  (-info
    ([_ _body] nil)
    ([_ _body _throwable] nil))
  (-warn
    ([_ _body] nil)
    ([_ _body _throwable] nil))
  (-error
    ([_ _body] nil)
    ([_ _body _throwable] nil)))

(extend-protocol LoggingMDC
  MDCAdapter
  (-get-mdc
    ([t k]
     (when k
       (.get t ^String (str k))))
    ([t k not-found]
     (when k
       (or (.get t ^String (str k))
           not-found))))
  (-put-mdc [t k v]
    (when k
      (.put t ^String (str k) ^String (str v)))
    t)
  (-remove-mdc [t k]
    (when k
      (.remove t ^String (str k)))
    t)
  (-clear-mdc [t]
    (.clear t)
    t)
  (-set-mdc [t m]
    (when m
      (.setContextMap t ^Map m))
    t)

  nil
  (-get-mdc
    ([_t _k] nil)
    ([_t _k _not-found] nil))
  (-put-mdc [_t _k _v] nil)
  (-remove-mdc [_t _k] nil)
  (-clear-mdc [_t] nil)
  (-set-mdc [_t _m] nil))

(def override-logger
  "Override of the default logger source, from symbol property `io.pedestal.log.overrideLogger`
  or environment variable `PEDESTAL_LOGGER`."
  (i/resolve-var-from "io.pedestal.log.overrideLogger" "PEDESTAL_LOGGER"))

(def ^:private override-logger-delay
  "Improves the ergonomics of overriding logging by delaying
  override logger resolution while maintaining backwards compatibility.

  This replaces override-logger, as it allows runtime setting of the property, rather
  than being locked into the property name when the namespace is first loaded."
  (delay (or override-logger
             (i/resolve-var-from "io.pedestal.log.overrideLogger" "PEDESTAL_LOGGER"))))

(defn make-logger
  "Returns a logger which satisfies the [[LoggerSource]] protocol."
  [^String logger-name]
  (or (when-let [override-logger @override-logger-delay]
        (override-logger logger-name))
      (LoggerFactory/getLogger logger-name)))

(def ^:private *default-formatter
  (delay
    (or (i/resolve-var-from "io.pedestal.log.formatter" "PEDESTAL_LOG_FORMATTER")
        pr-str)))

(defn default-formatter
  "Returns the default formatter (used to convert the event map to a string) used when the
  :io.pedestal.log/formatter key is not present in the log event.  The default is `pr-str`, but
  can be overridden via JVM property io.pedestal.log.formatter or
  environment variable `PEDESTAL_LOG_FORMATTER`."
  {:added "0.7.0"}
  []
  @*default-formatter)

(def ^{:deprecated "0.7.0"} log-level-dispatch
  "Used internally by the logging macros to map from a level keyword to a protocol method on
  [[LoggerSource]]."
  {:trace -trace
   :debug -debug
   :info  -info
   :warn  :error
   -warn  -error})

(defn- log-expr [form level keyvals]
  ;; Pull out :exception, otherwise preserve order
  (let [keyvals-map (apply array-map keyvals)
        exception' (:exception keyvals-map)
        logger' (gensym "logger")  ; for nested syntax-quote
        string' (gensym "string")
        log-method' (symbol "io.pedestal.log" (str "-" (name level)))
        formatter   (::formatter keyvals-map)]
    `(let [~logger' ~(or (::logger keyvals-map)
                         `(make-logger ~(name (ns-name *ns*))))]
       (when (io.pedestal.log/-level-enabled? ~logger' ~level)
         (let [formatter# ~(if formatter
                             formatter
                             `(default-formatter))
               ~string' (binding [*print-length* 80]
                          (formatter# ~(assoc (dissoc keyvals-map
                                                 :exception
                                                 ::logger
                                                 ::formatter)
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
                         (catch Throwable _
                           nil))]
    (.. ^Class bridge
        (getMethod "removeHandlersForRootLogger" (make-array Class 0))
        (invoke nil (make-array Object 0)))
    (.. ^Class bridge
        (getMethod "install" (make-array Class 0))
        (invoke nil (make-array Object 0)))))

;; SLF4J specific MDC utils
;; -------------------------

(def ^:dynamic *mdc-context*
  "This map is copied into the SLF4J MDC by the `with-context` macro.

  You are free to take control of
  it for MDC-related purposes as it doesn't directly affect Pedestal's
  logging implementation.

  This map also includes all options that were passed into `with-context`."
  {})

(def mdc-context-key
  "The key to use when formatting [[*mdc-context*]] for storage into the
  MDC (via [[-put-mdc]]).  io.pedestal.log uses only this single key of the
  underlying LoggingMDC implementation."
  "io.pedestal")

(defn ^:no-doc format-mdc
  "Used by macros to find the formatter stored in the MDC (or a default)
  and format it, excluding the ::formatter and ::mdc keys."
  [mdc-map]
  (let [formatter (or (::formatter mdc-map)
                      (default-formatter))]
    (formatter (dissoc mdc-map ::formatter ::mdc))))

(defn ^:no-doc put-formatted-mdc
  [mdc-map]
  (let [mdc (or (::mdc mdc-map)
                (MDC/getMDCAdapter))]
    (-put-mdc mdc mdc-context-key (format-mdc mdc-map))))


(defmacro with-context
  "Given a map of keys/values/options and a body,
  Set the map into the MDC via the *mdc-context* binding.

  The MDC used defaults to the SLF4J MDC unless the :io.pedestal.log/mdc
  option is specified (see Options).

  By default, the map is formatted into a string value and stored
  under the \"io.pedestal\" key.

  Caveats:
  SLF4J MDC, only maintains thread-local bindings, users are encouraged to
  use app-specific MDC implementations when needed.

  Since SLF4J MDC manages data on a per-thread basis, false
  information may be contained in the MDC if threads are
  recycled. Refer to the slf4j
  [docs](https://logback.qos.ch/manual/mdc.html#autoMDC) for more
  information.


  Options:

  Key         | Value          | Description
  ---         |---             |---
  ::formatter | Function       | Converts map to loggable value (a String), default via [[default-formatter]] is `pr-str`
  ::mdc       | [[LoggingMDC]] | Defaults to the SLFJ MDC.
  "
  [ctx-map & body]
  (if (empty? ctx-map)                                      ;; Optimize for the code-gen/dynamic case where the map may be empty
      `(do
         ~@body)
      `(let [old-ctx# *mdc-context*]
         ;; Note: /formatter goes into the MDC context but is filtered out when formatting.
         ;; This is to allow formatting in the finally block to use the formatter, if any,
         ;; of the old context.
         (binding [*mdc-context* (merge old-ctx# ~ctx-map)]
           (put-formatted-mdc *mdc-context*)
           (try
             ~@body
             (finally
               ;; This still seems to be the hard way to do this, as it feels like we should just
               ;; capture the previously written formatted string and revert to that on exit.
               (put-formatted-mdc old-ctx#)))))))
