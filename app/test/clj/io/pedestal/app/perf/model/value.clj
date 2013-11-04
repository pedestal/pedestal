; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.perf.model.value
  (:require [io.pedestal.app.perf.model.tracking-map :as tm]
            [io.pedestal.app.diff :as diff]
            [clojure.core.async :refer [go chan <! >!]]))

(defn- most-specific [paths]
  (:r (reduce (fn [{:keys [i r] :as a} p]
                (if (get-in i p)
                  a
                  {:i (assoc-in i p true)
                   :r (conj r p)}))
              {:i {} :r #{}}
              (sort-by count > paths))))

(defn- apply* [state path f args]
  (let [model (:model state)
        new-model (apply update-in model path f args)]
    (-> state
        (assoc :model new-model)
        (update-in [:paths] conj path))))

(defn- apply-with-tracking [state path f args]
  (let [tracking-map (tm/tracking-map (:model state))
        new-data-model (apply update-in tracking-map path f args)
        changed-paths (reduce into (vals (tm/changes new-data-model)))]
    (-> state
        (assoc :model @new-data-model)
        (update-in [:paths] into changed-paths))))

(defn apply-transform [data-model transform]
  (let [{:keys [model paths]}
        (reduce (fn [state [path f & args]]
                  (if (map? (get-in (:model state) path))
                    (apply-with-tracking state path f args)
                    (apply* state path f args)))
                {:paths [] :model data-model}
                transform)]
    {:model model
     :inform (diff/model-diff-inform (most-specific paths) data-model model)}))

(defn transform->inform [data-model inform-c]
  (let [transform-c (chan 10)]
    (go (loop [data-model data-model]
          (let [transform (<! transform-c)]
            (when transform
              (let [{:keys [model inform]} (apply-transform data-model transform)]
                (>! inform-c inform)
                (recur model))))))
    transform-c))
