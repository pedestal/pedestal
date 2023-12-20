; Copyright 2023 NuBank NA

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
  (:require [clojure.string :as str]))

(defn vec-conj
  [v value]
  (conj (or v []) value))

(defn into-vec
  [v coll]
  (into (or v []) coll))

(defn println-err [& s]
  (binding [*out* *err*]
    (apply println s)))

(defn resolve-var-from
  [property-name env-var]
  (let [resolver (fn [from-source from-name s]
                   (when s
                     (try
                       (let [slashx     (str/index-of s "/")
                             ns-str     (when slashx
                                          (subs s 0 slashx))
                             symbol-str (if slashx
                                          (subs s (inc slashx))
                                          s)
                             sym        (symbol ns-str symbol-str)
                             v          (requiring-resolve sym)]
                         (if v
                           @v
                           (println-err (format "WARNING: Symbol %s (from %s %s) does not exist"
                                                s
                                                from-source from-name))))
                       (catch Exception e
                         (println-err (format "ERROR: Could not resolve symbol %s (from %s %s): %s"
                                              s
                                              from-source from-name
                                              (ex-message e)))))))]
    (or (resolver "JVM property" property-name (System/getProperty property-name))
        (resolver "environment variable" env-var (System/getenv property-name)))))
