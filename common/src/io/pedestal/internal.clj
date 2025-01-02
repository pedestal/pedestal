; Copyright 2023-2024 Nubank NA

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

(defn- read-config
  [resource-path]
  (when-let [uri (io/resource resource-path)]
    (-> uri slurp edn/read-string)))

(def test-config (read-config "pedestal-test-config.edn"))
(def prod-config (read-config "pedestal-config.edn"))

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

(defn- resolver
  [from-source from-name val]
  (when-let [sym (to-sym val)]
    (let [value-source (if from-source
                         (str "from " from-source " " from-name)
                         "default value")]
      (try
        (let [v (requiring-resolve sym)]
          (if v
            @v
            (perr [:yellow
                   [:bold "WARNING: "]
                   (format "Symbol %s (%s) does not exist"
                           (str sym)
                           value-source)])))
        (catch Exception e
          (perr [:red
                 [:bold "ERROR: "]
                 (format "Could not resolve symbol %s (%s): %s"
                         (str sym)
                         value-source
                         (ex-message e))]))))))

(defn resolve-var-from
  "Resolves a var based on a JVM property, environment variable, or a default.

  Prints a warning if the symbol does not exist or other error requiring the var.

  May return nil."
  ([property-name env-var]
   (resolve-var-from property-name env-var nil))
  ([property-name env-var default-var-name]
   (or (resolver "JVM property" property-name (System/getProperty property-name))
       (resolver "environment variable" env-var (System/getenv env-var))
       ;; Defaults can be stored in config files, and the property name becomes a keyword
       ;; key.  The value can be a string or a qualified symbol.
       (let [config-key (keyword property-name)]
         (or (resolver "test configuration key" config-key (get test-config config-key))
             (resolver "configuration key" config-key (get prod-config config-key))))
       (when default-var-name
         (resolver nil nil default-var-name)))))

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

(defn deprecation-warning
  [label]
  (when-not (contains? @*deprecations label)
    (swap! *deprecations conj label)
    (perr
      [:yellow
       [:bold "WARNING: "]
       label
       " is deprecated and may be removed in a future release"]
      [:bold "\nCall stack: ... "]
      (call-stack 4))))

(defmacro deprecated
  [label & body]
  `(do
     (deprecation-warning ~label)
     ~@body))

(defn reset-deprecations
  []
  (swap! *deprecations empty))

(comment

  (reset-deprecations))

(defn is-a
  "Returns a spec predicate that checks if a value is an instance of the given class."
  [^Class clazz]
  #(instance? clazz %))
