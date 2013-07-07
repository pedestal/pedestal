; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

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

(defn send-transforms
  ([input-queue messages]
     (doseq [message messages]
       (p/put-message input-queue message)))
  ([input-queue transform-name messages]
     (send-transforms input-queue (map (partial msg/add-message-type transform-name) messages)))
  ([input-queue transform-name messages input-map]
     (send-transforms input-queue (msg/fill transform-name messages input-map))))

(defn send-on
  ([event-type dc input-queue transform-name messages]
     (send-on event-type
              dc
              input-queue
              (fn [e] (map (partial msg/add-message-type transform-name)
                          (produce-messages messages e)))))
  ([event-type dc input-queue messages]
     (event/listen! (-coerce-to-dom-content dc)
                    event-type
                    (fn [e]
                      (event/prevent-default e)
                      (send-transforms input-queue (produce-messages messages e))))))

(defn send-on-click [& args]
  (apply send-on :click args))

(defn send-on-keyup [& args]
  (apply send-on :keyup args))

(defn collect-inputs [input-map]
  (reduce (fn [a [dc k]]
            (assoc a k (value dc)))
          {}
          input-map))

(defn collect-and-send [event-type dc input-queue transform-name messages input-map]
  (send-on event-type dc input-queue
           (fn [_]
             (msg/fill transform-name messages (collect-inputs input-map)))))

(defn remove-event [event-type dc]
  (event/unlisten! (-coerce-to-dom-content dc) event-type))

(defn remove-click-event [dc]
  (remove-event :click dc))
