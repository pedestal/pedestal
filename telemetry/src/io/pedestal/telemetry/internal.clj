; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.telemetry.internal
  "Internal utilities used by Pedestal telemetry; subject to change without notice."
  {:no-doc true}
  (:require [io.pedestal.internal :as i])
  (:import (clojure.lang Keyword Symbol)
           (io.opentelemetry.api.common Attributes AttributeKey AttributesBuilder)))

(defprotocol AttributeKVPair

  (attr-kv-pair [v k]))

(extend-protocol AttributeKVPair

  String

  (attr-kv-pair [v k]
    [(AttributeKey/stringKey k) v])

  Boolean

  (attr-kv-pair [v k]
    [(AttributeKey/booleanKey k) v])

  Symbol

  (attr-kv-pair [v k]
    [(AttributeKey/stringKey k) (str v)])

  Keyword
  (attr-kv-pair [v k]
    [(AttributeKey/stringKey k) (-> v str (subs 1))])

  Long
  (attr-kv-pair [v k]
    [(AttributeKey/longKey k) v])

  Integer
  (attr-kv-pair [v k]
    [(AttributeKey/longKey k) (long v)])

  Double
  (attr-kv-pair [v k]
    [(AttributeKey/doubleKey k) v])

  Float
  (attr-kv-pair [v k]
    [(AttributeKey/doubleKey k) (double v)])

  ;; We don't/can't handle arrays, and nils are not allowed here
  )

(defn to-str
  [v]
  (cond
    (string? v) v
    (keyword? v) (-> v str (subs 1))
    (symbol? v) (str v)))

(defn convert-key
  ^String [k]
  (cond
    (string? k) k
    (keyword? k) (subs (str k) 1)
    (symbol? k) (str k)
    ;; TODO: Maybe support Class?

    :else
    (throw (ex-info (str "Invalid attribute key type: " (-> k class .getName))
                    {:key k}))))

(defn kv->pair
  [k v]
  (let [k' (convert-key k)]
    (attr-kv-pair v k')))

(defn map->Attributes
  ^Attributes [attributes]
  (if-not (seq attributes)
    (Attributes/empty)
    (->> ^AttributesBuilder
         (reduce-kv (fn [^AttributesBuilder b k v]
                      (let [[k' v'] (kv->pair k v)]
                        (.put b ^AttributeKey k' v')))
                    (Attributes/builder)
                    attributes)
         .build)))

(defn- create
  [what property-name env-var default-var-name]
  (when-let [v (i/resolve-var-from property-name env-var default-var-name)]
    (try
      (v)
      (catch Exception e
        (throw (RuntimeException. (format "Error invoking function %s (to create default %s source)"
                                          (str v)
                                          what)
                                  e))))))

(defn create-default-metric-source
  []
  (create "metric" "io.pedestal.telemetry.metric-source" "PEDESTAL_METRICS_SOURCE"
          "io.pedestal.telemetry.otel-global-init/metric-source"))

(defn create-default-tracing-source
  []
  (create "tracing" "io.pedestal.telemetry.tracing-source" "PEDESTAL_TRACING_SOURCE"
          "io.pedestal.telemetry.otel-global-init/tracing-source"))

