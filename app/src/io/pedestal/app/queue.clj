; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app.queue
  "A very simple application message queue implementation which can be used from both
  Clojure and ClojureScript. In the future, there will be both Clojure and ClojureScript
  implementations of this queue which will take advantage of the capabilities of each
  platform."
  (:require [io.pedestal.app.protocols :as p]
            [io.pedestal.app.messages :as msg]
            [io.pedestal.app.util.platform :as platform]))

(defn- pop-message-internal [queue-state]
  (let [queues (:queues queue-state)
        priority (if (seq (:high queues)) :high :low)]
    (-> queue-state
        (assoc :item (first (priority queues)))
        (update-in [:queues priority] #(vec (rest %))))))

(defn- not-empty? [queue]
  (or (seq (get-in queue [:queues :high]))
      (seq (get-in queue [:queues :low]))))

(defn- process-next-item [queue f]
  (when (not-empty? @queue)
    (when-let [item (:item (swap! queue pop-message-internal))]
      (platform/log-exceptions f item))))

(defn- count-queues [queues]
  (+ (count (:high queues))
     (count (:low queues))))

(comment
  ;; We record the functions interested in messages, so that we can
  ;; restart the message processing again once a message arrives in
  ;; the queue.
  )

(defrecord AppMessageQueue [state]
  p/PutMessage
  (put-message [this message]
    (let [priority (if (= (msg/priority message) :high) :high :low)
          queues (:queues (swap! state update-in [:queues priority] conj message))
          new-count (count-queues queues)]
      (when (= new-count 1)
        (when-let [fns (:take-fns @state)]
          (platform/create-timeout 0 #(doseq [f fns]
                                        (process-next-item state f)))))
      new-count))
  p/TakeMessage
  (pop-message [this]
    (:item (swap! state pop-message-internal)))
  (take-message [this f]
    (swap! state update-in [:take-fns] conj f)
    (process-next-item state f)))

(defn queue-length [app-message-queue]
  (let [queues (-> app-message-queue :state deref :queues)]
    (count-queues queues)))

(defn queue [name]
  (->AppMessageQueue (atom {:queues {:high [] :low []}
                            :item nil
                            :name name})))

(defn consume-queue
  "Recursively process each item on the given queue with the
  provided function."
  [queue f]
  (p/take-message queue
                  (fn [message]
                    (f message)
                    (consume-queue queue f))))
