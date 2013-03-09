;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.app.util.log
  (:require [io.pedestal.app.util.observers :as observers]))

(defn log
  "Logs a message at level (a keyword). The message will be a map
  constructed from the key-value pairs supplied."
  [level & keyvals]
  (observers/publish :log (assoc (apply hash-map keyvals) :level level)))

(defn trace
  "Logs a trace message. Argument is a quoted list representing the
  function being called, with arguments."
  [call-expr]
  (log :trace :call call-expr))

(defn error
  "Logs an error message."
  [& keyvals]
  (apply log :error keyvals))

(defn debug
  "Logs a :debug level message. Use this level for debugging output
  which is less verbose than :trace."
  [& keyvals]
  (apply log :debug keyvals))

(defn info
  "Logs an :info level message. Use this level for information we may
  want to record in tests of the production system."
  [& keyvals]
  (apply log :info keyvals))

(defn warn
  "Logs a :warn level message."
  [& keyvals]
  (apply log :warn keyvals))
