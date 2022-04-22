(ns override-logging.legacy-log
  "Houses the override logger implementation to be used
  by `io.pedestal.log`.

  Since the `io.pedestal.log/LoggerSource` protocol and override
  logger resolution is done in the same namespace, you can't `require`
  the `io.pedestal.log` namespace in the `override-logging.log``ns` form.
  You must do three things, in order:
  1. `declare` the logger factory function
  2. `require` the `io.pedestal.log` namespace
  3. Define an `io.pedestal.log/LoggerSource` implementation.

  Refer to the docstring of `io.pedestal.log/override-logger` for
  guidance on how to configure the override logger.")

;; 1. `declare` the logger factory fn.
(declare make-logger)

;; 2. `require` `io.pedestal.log`
(require 'io.pedestal.log)

;; The log implementation below is purely for demonstration
;; purposes.

(defn- log-msg
  "log fn which adds some metadata and ships the log message.
  `tap>` is used here for convenience."
  [level ns-str body & [ex]]
  (tap> (cond-> {:logger "OverrideLogger"
                 :level level
                 :ns ns-str
                 :message body}
          ex
          (assoc :ex ex))))

(def levels
  "Log level hierarchy"
  (-> (make-hierarchy)
      (derive :debug :trace)
      (derive :info :debug)
      (derive :warn :info)
      (derive :error :warn)))

(def ^:dynamic level
  "The current log level."
  :info)

;; 3. Define the override logger
(defrecord OverrideLogger [ns-str]
  io.pedestal.log/LoggerSource
  (-level-enabled? [_ l] (isa? levels l level))
  (-trace [t body] (log-msg :trace ns-str body))
  (-debug [t body] (log-msg :debug ns-str  body))
  (-info [t body] (log-msg :info ns-str  body))
  (-warn [t body] (log-msg :warn ns-str  body))
  (-error [t body] (log-msg :error ns-str  body))
  (-error [t body ex] (log-msg :error ns-str body ex)))

(defn- make-logger* [ns-str]
  (->OverrideLogger ns-str))

;; Keep in mind that the factory fn will be called multiple times during macro expansion.
(def make-logger
  "Memoized factory function."
  (memoize make-logger*))
