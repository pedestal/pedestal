(ns io.pedestal.app.route
  "Route transform messages from incoming channel to an outbound channel."
  (:require [io.pedestal.app.match :as match])
  (:use [clojure.core.async :only [go chan <! put!]]))

(defn add-channel [idx [_ _ config]]
  (match/index idx [config]))

(defn remove-channel [idx [_ _ config]]
  (match/remove-from-index idx [config]))

(defn route-message [idx transform-message]
  (doseq [[c patterns inform] (match/match idx transform-message)]
    (put! c inform))
  idx)

(defn router [id in]
  (go (loop [idx {}]
        (let [transform-message (<! in)]
          (when transform-message
            (let [new-idx (cond (and (= (first transform-message) id)
                                     (= (second transform-message) :add))
                                (add-channel idx transform-message)

                                (and (= (first transform-message) id)
                                     (= (second transform-message) :remove))
                                (remove-channel idx transform-message)

                                :else (route-message idx transform-message))]
              (recur new-idx)))))))
