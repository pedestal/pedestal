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
            [clojure.string :as string]))

(defn vec-conj
  [v value]
  (conj (or v []) value))

(defn into-vec
  [v coll]
  (into (or v []) coll))

(defn println-err
  [& s]
  (binding [*out* *err*]
    (apply println s)))

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
            (println-err (format "WARNING: Symbol %s (%s) does not exist"
                                 (str sym)
                                 value-source))))
        (catch Exception e
          (println-err (format "ERROR: Could not resolve symbol %s (%s): %s"
                               (str sym)
                               value-source
                               (ex-message e))))))))

(defn resolve-var-from
  "Resolves a var based on a JVM property, environment variable, or a default.

  Prints a warning if the symbol does not exist or other error requiring the var.

  May return nil."
  ([property-name env-var]
   (resolve-var-from property-name env-var nil))
  ([property-name env-var default-value]
   (or (resolver "JVM property" property-name (System/getProperty property-name))
       (resolver "environment variable" env-var (System/getenv env-var))
       ;; Defaults can be stored in config files, and the property name becomes a keyword
       ;; key.  The value can be a string or a qualified symbol.
       (let [config-key (keyword property-name)]
         (or (resolver "test configuration key" config-key (get test-config config-key))
             (resolver "configuration key" config-key (get prod-config config-key))))
       (when default-value
         (resolver nil nil default-value)))))

(def *deprecations (atom #{}))

(defn deprecation-warning
  [k label]
  (when-not (contains? @*deprecations k)
    (swap! *deprecations conj k)
    (println-err (str "WARNING: " label
                      " is deprecated and may be removed in a future release (in namespace "
                      *ns* ")"))))

(defmacro deprecated
  [label & body]
  (let [ns-str (str *ns*)]
    `(let [label# (str ~label)
           k#     (str ~ns-str ":" label#)]
       (deprecation-warning k# label#)
       ~@body)))

(defn reset-deprecations
  []
  (swap! *deprecations empty))

