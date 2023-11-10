; Copyright 2023 NuBank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:no-doc io.pedestal.metrics.internal
  "Internal utils subject to change without notice."
  (:require [clojure.string :as string])
  (:import (io.micrometer.core.instrument Counter Metrics)))

(def *metric-cache (atom {}))

(defn ^String munge-name
  [clojure-name]
  (cond
    (keyword? clojure-name)
    (-> (str clojure-name)                                  ; keep ns, strip off colon
        (subs 1)
        munge-name)

    (symbol? clojure-name)
    (munge-name (str clojure-name))

    (string? clojure-name)
    ;; Convert `/`, `-`, and `_` to `.`
    (string/replace clojure-name #"[/\-_]+" ".")

    :else
    (throw (ex-info (str "Type " (type clojure-name) " not a valid type for conversion to metric name")
                    {:value clojure-name}))))

(comment
  (munge-name 'clojure.string/replace-by)
  (munge-name :foo.bar/-baz)
  )

(defn- get-or-create
  [metric-name tag-kvs create-fn]
  (let [metric-key (into [metric-name] tag-kvs)
        existing (get *metric-cache metric-key)]
    (if existing
      existing
      (let [new-metric (create-fn (munge-name metric-name) (mapv str tag-kvs))]
        (swap! *metric-cache assoc metric-name new-metric)
        new-metric))))

(defn ^Counter counter
  [metric-name tag-kvs]
  (get-or-create metric-name tag-kvs
                 (fn [^String metric-name ^Iterable tags]
                   (Metrics/counter metric-name tags))))

(comment
  (counter :foo/bar [])

  )

