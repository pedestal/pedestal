(ns io.pedestal.app.route
  "Route transform messages from incoming channel to an outbound channel."
  (:require [io.pedestal.app.match :as match])
  (:use [clojure.core.async :only [go chan <! put!]]))

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
            (when transform-message
              (swap! idx update-index (filter to-router? transform-message))
              (doseq [[c patterns msg] (match/match @idx (vec (remove to-router? transform-message)))]
                (try
                  (>! c msg)
                  (catch Exception e
                    (swap! idx remove-channel (into [c] (for [ps patterns p ps] p))))))
              (recur)))))))
