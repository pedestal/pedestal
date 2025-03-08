; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.async-events
  "Helpers for async tests."
  (:require [clojure.core.async :as async :refer [put! chan]]
            [clojure.test :refer [report]]
            [net.lewisship.trace :refer [trace]]))

(def ^:private events-chan nil)

(defn events-chan-fixture [f]
  (with-redefs [events-chan (chan 10)]
    (f)))


(defn <event!!
  []
  (async/alt!!
    events-chan ([event]
                 #_(trace :event event)
                 event)

    ;; A fake event for when things are broken:
    (async/timeout 75) [::timed-out]))

(defmacro expect-event
  "Events are written with [[write-event]], as a vector where the first element is the type (:open, :close, :text, etc.).
  Expects a particular kind of event to be in the channel.
  Consumes and ignores events until a match is found, or a timeout occurs.
  Reports a failure on timeout that includes any consumed events.

  Returns the rest of the event (i.e., after the leading type term is stripped out) on success, or nil on failure."
  [expected-kind]
  `(let [expected-kind# ~expected-kind]
     (loop [skipped# []]
       (let [[kind# :as event#] (<event!!)]
         (cond
           (= kind# expected-kind#)
           (do
             (report {:type :pass})
             (rest event#))

           (= ::timed-out kind#)
           (do
             (report {:type     :fail
                      :message  "Expected event was not delivered"
                      :expected expected-kind#
                      :actual   (conj skipped# event#)})
             nil)

           :else
           (recur (conj skipped# event#)))))))

(defn write-event
  "Writes an event, a tuple of type plus any data, into the channel."
  [type & data]
  (let [event (apply vector type data)]
    (if events-chan
      (put! events-chan event)
      (println "*** Late event:" event))))
