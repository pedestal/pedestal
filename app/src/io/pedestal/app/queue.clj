;; Copyright (c) 2013 Relevance, Inc. All rights reserved.

(ns ^:shared io.pedestal.app.queue
  "A very simple application message queue implementation which can be used from both
  Clojure and ClojureScript. In the future, there will be both Clojure and ClojureScript
  implementations of this queue which will take advantage of the capabilities of each
  platform."
  (:require [io.pedestal.app.protocols :as p]
            [io.pedestal.app.util.platform :as platform]))

(defn- pop-message [queue-state]
  (let [{:keys [queue]} queue-state]
    (if (seq queue)
      (assoc queue-state
        :item (first queue)
        :queue (vec (rest queue)))
      (assoc queue-state :item nil))))

(defn- process-next-item [queue f]
  (if (seq (:queue @queue))
    (if-let [item (:item (swap! queue pop-message))]
      (f item)
      (platform/create-timeout 10 (fn [] (process-next-item queue f))))
    (platform/create-timeout 10 (fn [] (process-next-item queue f)))))

(defrecord AppMessageQueue [state]
  p/PutMessage
  (put-message [this message]
    (let [q (swap! state update-in [:queue] conj message)]
      (count (:queue q))))
  p/TakeMessage
  (take-message [this f]
    (process-next-item state f)))

(defn queue [name]
  (->AppMessageQueue (atom {:queue [] :item nil :name name})))
