; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app.render
  (:require [io.pedestal.app.protocols :as p]
            [io.pedestal.app.util.platform :as platform]
            [io.pedestal.app.tree :as tree]))

(defn- consume-app-model-queue [queue in-queue app-model render-fn]
  (p/take-message queue
                  (fn [message]
                    (let [old-app-model @app-model
                          new-app-model (swap! app-model tree/apply-deltas (:deltas message))
                          deltas (tree/since-t new-app-model (tree/t old-app-model))]
                      (render-fn deltas in-queue)
                      (consume-app-model-queue queue in-queue app-model render-fn)))))

(defn consume-app-model [app render-fn]
  (let [app-model (atom tree/new-app-model)]
    (consume-app-model-queue (:app-model app) (:input app) app-model render-fn)
    app-model))

(defn log-fn [deltas]
  (platform/log-group
   "<----------------------------------------------------------------------"
   "---------------------------------------------------------------------->"
   deltas))
