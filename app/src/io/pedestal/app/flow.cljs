; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.flow
  (:require [io.pedestal.app.model :as model]
            [io.pedestal.app.map :as mapper])
  (:use [cljs.core.async :only [chan <! >!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def ^:private marker [[[::marker] ::marker]])

(defn- marker? [m]
  (= m marker))

(defn- get-until-marker [cin]
  (go (loop [transforms []]
        (let [t (<! cin)]
          (if (and t (not (marker? t)))
            (recur (conj transforms t))
            transforms)))))

(defn- squash-old-and-new [inform]
  (let [[_ _ old] (first inform)
        [_ _ _ new] (last inform)]
    (mapv (fn [[path change]] [path change old new]) inform)))

(defn- flow-loop [model-inform model-transform map-inform map-transform]
  ;; TODO: Count iterations and blow up if maximum is exceeded
  (go (loop [flow-changes []]
        (let [model-changes (<! model-inform)]
          (>! map-inform model-changes)
          (>! map-inform marker)
          (let [new-transforms (<! (get-until-marker map-transform))
                flow-changes (into flow-changes model-changes)]
            (if (seq new-transforms)
              ;; create one single transaction for all flow changes
              (let [transform-message (reduce into [] new-transforms)]
                (>! model-transform transform-message)
                (recur flow-changes))
              (squash-old-and-new flow-changes)))))))

(defn transform->inform
  "Given a data model, a configuration and an inform channel, return a transform
  channel. When a transform message is put on the transform channel, the
  resulting inform message will be put on the inform channel.

  The configuration is a vector passed to mapper/inform->transforms. When a
  transform message is put on the transform channel, model/transform->inform is
  called to generate an inform. Based on the given config, an inform can produce
  new transforms. If it does, the new transform produces a new inform and this
  loop continues until the config does not trigger new transforms."
  ([data-model config inform-c]
     (transform->inform data-model config mapper/default-args-fn inform-c))
  ([data-model config args-fn inform-c]
     (let [transform-c (chan 10)
           model-inform (chan 10)
           model-transform (model/transform->inform data-model model-inform)
           map-transform (chan 10)
           marker-fn (fn [_ inform-message] [inform-message])
           config (conj config [marker-fn [::marker] ::marker])
           map-inform (mapper/inform->transforms config map-transform args-fn)]
       (go (loop []
             (when-let [transform-message (<! transform-c)]
               (>! model-transform transform-message)
               (let [flow-c (flow-loop model-inform
                                       model-transform
                                       map-inform
                                       map-transform)]
                 (>! inform-c (<! flow-c))
                 (recur)))))
       transform-c)))
