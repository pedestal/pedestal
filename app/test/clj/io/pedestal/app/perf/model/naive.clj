; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.perf.model.naive
  (:require [io.pedestal.app.diff :as diff]
            [clojure.core.async :refer [go chan <! >!]]))

(defn apply-transform
  "Given a model and a transform message, return a map with an
  updated model and the inform message which describes the changes
  made to the model."
  [old-model transform]
  (let [new-model (reduce (fn [m [path f & args]]
                            (apply update-in m path f args))
                          old-model
                          transform)]
    {:model new-model
     :inform (diff/model-diff-inform old-model new-model)}))

(defn transform->inform
  "Given a model and an inform channel, returns a transform channel.
  When a transform message is put on the transform channel, the
  resulting inform message will be put on the inform channel."
  [data-model inform-c]
  (let [transform-c (chan 10)]
    (go (loop [data-model data-model]
          (let [transform (<! transform-c)]
            (when transform
              (let [{:keys [model inform]} (apply-transform data-model transform)]
                (>! inform-c inform)
                (recur model))))))
    transform-c))
