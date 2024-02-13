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
  (:import (io.opentelemetry.api.common Attributes AttributeKey AttributesBuilder)))

(defn convert-name
  [v]
  (cond
    (string? v) v
    (keyword? v) (-> v str (subs 1))
    (symbol? v) (str v)))

(defn- convert-key
  ^AttributeKey [k]
  (let [s (cond
            (string? k) k
            (keyword? k) (subs (str k) 1)
            (symbol? k) (str k)
            ;; TODO: Maybe support Class?

            :else
            (throw (ex-info (str "Invalid Tag key type: " (-> k class .getName))
                            {:key k})))]
    (AttributeKey/stringKey s)))

(defn map->Attributes
  ^Attributes [attributes dissoc-key]
  (let [attributes' (apply dissoc attributes dissoc-key)]
    (if-not (seq attributes')
      (Attributes/empty)
      (->> (reduce-kv (fn [^AttributesBuilder b k v]
                        (.put b (convert-key k) v))
                      (Attributes/builder)
                      attributes')
           .build))))

