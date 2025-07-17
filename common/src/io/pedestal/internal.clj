; Copyright 2023-2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:no-doc io.pedestal.internal
  "Internal utilities, not for reuse, subject to change at any time."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clj-commons.ansi :refer [perr]]
            [clj-commons.format.exceptions :as e]))

(defn vec-conj
  [v value]
  (conj (or v []) value))

(defn- read-resource
  [resource-path]
  (when-let [uri (io/resource resource-path)]
    (-> uri slurp edn/read-string)))

(def test-config (read-resource "pedestal-test-config.edn"))
(def prod-config (read-resource "pedestal-config.edn"))

(defn- to-sym
  [val]
  (cond
    (nil? val) nil

    (string? val)
    (let [slashx     (string/index-of val "/")
          ns-str     (when slashx
                       (subs val 0 slashx))
          symbol-str (if slashx
                       (subs val (inc slashx))
                       val)]
      (symbol ns-str symbol-str))

    (qualified-symbol? val) val

    :else
    (throw (IllegalArgumentException. (str val " is not a string or qualified symbol")))))

(defn- value-source
  [from-source from-name]
  (if from-source
    (str "from " from-source " " from-name)
    "default value"))

(defn- fn-resolver
  [from-source from-name val]
  (when-let [sym (to-sym val)]
    (try
      (let [v (requiring-resolve sym)]
        (if v
          @v
          (perr [:yellow
                 [:bold "WARNING: "]
                 (format "Symbol %s (%s) does not exist"
                         (str sym)
                         (value-source from-source from-name))])))
      (catch Exception e
        (perr [:red
               [:bold "ERROR: "]
               (format "Could not resolve symbol %s (%s): %s"
                       (str sym)
                       (value-source from-source from-name)
                       (ex-message e))])))))

(defn- boolean-resolver
  [_ _ val]
  (cond
    (nil? val)
    nil

    (boolean? val)
    val

    (string? val)
    (Boolean/valueOf ^String val)

    :else
    (throw (IllegalArgumentException. (str val " is not a string or boolean")))))

(defn- keyword-resolver
  [_ _ val]
  (cond
    (nil? val)
    val

    (keyword? val)
    val

    (string? val)
    (keyword val)

    :else
    (throw (IllegalArgumentException. (str val " is not a string or keyword")))))

(def ^:private kw->resolver
  {:fn fn-resolver
   :boolean boolean-resolver
   :keyword keyword-resolver})

(defn read-config
  "Reads a configuration value, and converts it to a particular type.

  A configuration value may come from a JVM system property, an environment variable,
  from one of two configuration files, or from a provided default value.

  Prints a warning if the config value does not exist or other error requiriBng the var.

  May return nil."
  [property-name env-var & {:keys [as default-value]
                            :or   {as :fn}}]
  (let [resolver (get kw->resolver as)]
    (or (resolver "JVM property" property-name (System/getProperty property-name))
        (resolver "environment variable" env-var (System/getenv env-var))
        ;; Defaults can be stored in config files, and the property name becomes a keyword
        ;; key.
        (let [config-key (keyword property-name)]
          (or (resolver "test configuration key" config-key (get test-config config-key))
              (resolver "configuration key" config-key (get prod-config config-key))))
        (resolver nil nil default-value))))

(defn- truncate-to
  [limit coll]
  (let [n (count coll)]
    (if (<= n limit)
      coll
      (drop (- n limit) coll))))

(defn- call-stack
  [limit]
  (->> (Thread/currentThread)
       .getStackTrace
       (drop 1)                                             ;; call to .getStackTrace()
       e/transform-stack-trace
       e/filter-stack-trace-maps
       (remove :omitted)
       (drop-while #(string/starts-with? (:name %) "io.pedestal.internal/"))
       ;; Remove some cruft, in the hope of better identifying what's really been called
       (remove #(string/starts-with? (:name %) "clojure.core"))
       (remove #(string/ends-with? (:name %) "/G"))         ; protocol dispatch fns
       (map e/format-stack-frame)
       (map :name)
       reverse                                              ;; outermost -> innermost
       (truncate-to limit)
       (interpose " -> ")))

(def *deprecations (atom #{}))

(def *suppress?
  (delay (read-config "io.pedestal.suppress-deprecation-warnings"
                      "PEDESTAL_SUPPRESS_DEPRECATION_WARNINGS"
                      :as :boolean)))

(defn deprecated*
  [label in noun]
  (when-not (contains? @*deprecations label)
    (swap! *deprecations conj label)
    (perr
      [:yellow
       [:bold "WARNING: "]
       (or noun label)
       " "
       (if in
         (list "was deprecated in version "
               [:bold in])
         "has been deprecated")
       " and may be removed in a future release"]
      [:bold "\nCall stack: ... "]
      (call-stack 4))))

(defmacro deprecated
  [label & {:keys [in noun]}]
  `(when-not @*suppress?
     (deprecated* ~label ~in ~noun)))

(defn reset-deprecations
  []
  (swap! *deprecations empty))

(comment

  (reset-deprecations))

(defn is-a
  "Returns a spec predicate that checks if a value is an instance of the given class."
  [^Class clazz]
  #(instance? clazz %))
