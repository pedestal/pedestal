;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.app.render.events
  (:require [io.pedestal.app.protocols :as p]
            [io.pedestal.app.messages :as msg]
            [domina :as d]
            [domina.events :as event]
            [goog.object :as gobj]
            [goog.events :as gevents]))

(defprotocol DomContentCoercible
  (-coerce-to-dom-content [this]))

(extend-protocol DomContentCoercible
  string
  (-coerce-to-dom-content [this]
    (d/by-id this))
  default
  (-coerce-to-dom-content [this]
    (cond (satisfies? d/DomContent this) this)))

(defn value [dc]
  (.-value (-coerce-to-dom-content dc)))

(defn set-value! [dc x]
  (set! (.-value (-coerce-to-dom-content dc)) x))

(defn- produce-messages [messages e]
  (if (fn? messages) (messages e) messages))

(defn send-on
  ([event-type dc dispatcher transform-name messages]
     (send-on event-type
              dc
              dispatcher
              (fn [e] (map (partial msg/add-message-type transform-name)
                          (produce-messages messages e)))))
  ([event-type dc dispatcher messages]
     (event/listen! (-coerce-to-dom-content dc)
                    event-type
                    (fn [e]
                      (event/prevent-default e)
                      (doseq [message (produce-messages messages e)]
                        (p/put-message dispatcher message))))))

(defn send-on-click [& args]
  (apply send-on :click args))

(defn send-on-keyup [& args]
  (apply send-on :keyup args))

(defn collect-inputs [input-map]
  (reduce (fn [a [dc k]]
            (assoc a k (value dc)))
          {}
          input-map))

(defn collect-and-send [event-type dc dispatcher transform-name messages input-map]
  (send-on event-type dc dispatcher
           (fn [_]
             (msg/fill transform-name messages (collect-inputs input-map)))))
