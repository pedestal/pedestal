; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.route
  "Route transform messages from incoming channel to an outbound channel."
  (:require [io.pedestal.app.match :as match]
            [io.pedestal.app.util.log :as log])
  (:use [cljs.core.async :only [chan <! put!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn- add-channel [idx config]
  (match/index idx [config]))

(defn- remove-channel [idx config]
  (match/remove-from-index idx [config]))

(defn- update-index [idx transform-message]
  (reduce (fn [i [_ event config]]
            (case event
              :add
              (add-channel i config)
              :remove
              (remove-channel i config)
              i))
          idx
          transform-message))

(defn router [id in]
  (let [to-router? (fn [transformation] (= (first transformation) id))
        idx (atom {})]
    (go (loop []
          (let [transform-message (<! in)]
            (log/info :in :router :id id :transform transform-message)
            (when transform-message
              (swap! idx update-index (filter to-router? transform-message))
              (doseq [[c patterns msg] (match/match @idx (vec (remove to-router? transform-message)))]
                (try
                  (>! c msg)
                  (catch js/Object e
                    (swap! idx remove-channel (into [c] (for [ps patterns p ps] p))))))
              (recur)))))))

