; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.model
  (:require [io.pedestal.app.data.tracking-map :as tm]
            [cljs.core.async :refer [chan <! >!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn- merge-changes [old-changes new-changes]
  (merge-with into old-changes new-changes))

(defn- update-state-with
  "Updates the state map based on applying update-in on the data-model
  for the given path, f and args."
  [state path f args]
  (let [tracking-map (tm/tracking-map (:old state))
        new-data-model (apply update-in tracking-map path f args)]
    (-> state
        (assoc :new @new-data-model)
        (update-in [:change] merge-changes (tm/changes new-data-model)))))

(defn- build-out-messages
  "Builds the outgoing messages based on the type of change."
  [{:keys [old new change]}]
  (reduce
   (fn [accum change-type]
     (into accum (map #(vector % change-type)
                      (sort (change-type change)))))
   []
   [:added :removed :updated]))

(defn- transform-to-inform*
  "Builds an inform message returning it in a state map. The state starts
  with an :old key and returns with additional keys of :change, :out and :new.
  The :out key is the inform message, :new is the new data-model and :change
  is a change map containing :added, :removed and :updated keys."
  [data-model message]
  (let [[path transform-fn & args] message]
    (-> {:old data-model}
        (update-state-with path transform-fn args)
        (as-> state
          (assoc state :out (build-out-messages state))))))

(defn transform-to-inform
  "Given a data-model and a transform message, return an inform message."
  [data-model messages]
  (let [{msgs :messages new-model :data-model}
        (reduce (fn [{:keys [data-model messages]} message]
                  (let [state (transform-to-inform* data-model message)]
                    {:data-model (:new state)
                     :messages (into messages (:out state))}))
                {:data-model data-model :messages []}
                messages)]
    [(mapv #(conj % data-model new-model) msgs)
     new-model]))

(defn transform->inform
  "Given a data model and an inform channel, returns a transform channel.
  When a transform message is put on the transform channel, the resulting
  inform message will be put on the inform channel."
  [data-model inform-c]
  (let [transform-c (chan 10)]
    (go (loop [data-model data-model]
          (let [transform (<! transform-c)]
            (when transform
              (let [[inform-msgs new-model] (transform-to-inform data-model transform)]
                (>! inform-c inform-msgs)
                (recur new-model))))))
    transform-c))
