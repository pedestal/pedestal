; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor.chain.debug
  "Tools to help debug interceptor chain execution."
  {:added "0.7.0"}
  (:require [clojure.set :as set]
            [io.pedestal.log :as log]))

(declare delta*)

(def ^:private omitted-value '...)

(def default-omit-set
  "The default key paths to be omitted when producing delta output."
  #{[:response :body]
    [:request :body]})


(defn- omit-values
  [omit-set key-path value]
  (cond
    (omit-set key-path)
    omitted-value

    (map? value)
    (reduce-kv (fn [m k v]
                 (assoc m k
                        (omit-values omit-set (conj key-path k) v)))
               {}
               value)

    :else
    value))

(defn- delta-maps
  [omit-set deltas key-path original modified]
  (let [o-keys      (-> original keys set)
        m-keys      (-> modified keys set)
        shared-keys (set/intersection o-keys m-keys)]
    (reduce (fn [m k]
              (cond
                (and (contains? shared-keys k))
                (delta* omit-set
                        m
                        (conj key-path k)
                        (get original k)
                        (get modified k))

                (contains? m-keys k)
                (let [key-path' (conj key-path k)]
                  (assoc-in m [:added key-path']
                            (omit-values omit-set key-path' (get modified k))))

                :else
                (let [key-path' (conj key-path k)]
                  (assoc-in m [:removed key-path']
                            (omit-values omit-set key-path' (get original k))))))
            deltas
            (set/union o-keys m-keys))))

(defn- delta*
  [omit-set deltas key-path original modified]
  (cond
    (= original modified)
    deltas

    (and (map? original) (map? modified))
    (delta-maps omit-set deltas key-path original modified)

    :else
    (assoc-in deltas [:changed key-path]
              (if (omit-set key-path)
                omitted-value
                {:from original
                 :to   modified}))))

(defn- delta
  "Return map with keys :added, :removed, and :changed ..."
  [omit-set original modified]
  (delta* (or omit-set default-omit-set) {} [] original modified))

(defn debug-observer
  "Returns an observer function that logs, at debug level, the interceptor name, stage, execution id,
  and a description of context changes.

  The context changes are in the form of a map.
  The :added key is a map of key path to added values.
  The :removed key is a map of key path to removed values.
  The :changed key is a map of key path to a value change, a map of :from and :to.


  Options map:

  | Key            | Type            | Description
  |---             |---              |---
  | :omit          | set or function | Identifies key paths, as vectors, to omit in the description.
  | :changes-only? | boolean         | If true, then log only when the context changes.

  The :omit option is used to prevent certain key paths from appearing in the result delta; the value
  for these is replaced with `...`.  It is typically a set, but can also be a function that accepts
  a key path vector.

  The default for :omit is `#{[:response :body] [:request :body]}`. This omits data that can be both
  sensitive and verbose."
  ([]
   (debug-observer nil))
  ([options]
   (let [{:keys [omit changes-only?]} options
         always? (not changes-only?)]
     (fn [event]
       (let [{:keys [execution-id stage interceptor-name context-in context-out]} event
             changes? (not= context-in context-out)]
         (when (or always? changes?)
           (log/debug
             :interceptor interceptor-name
             :stage stage
             :execution-id execution-id
             :context-changes (when changes?
                                (delta omit context-in context-out)))))))))

