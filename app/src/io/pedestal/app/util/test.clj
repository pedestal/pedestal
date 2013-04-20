; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.util.test
  (:require [io.pedestal.app :as app]
            [io.pedestal.app.util.platform :as platform]))

(defn run-sync! [app script & {:keys [begin timeout wait-for]}]
  (assert (or (nil? begin)
              (= begin :default)
              (vector? begin))
          "begin must be nil, the keyword :default or a vector of messges")
  (assert (or (nil? wait-for)
              (every? #(contains? #{:output :app-model} %) wait-for))
          "wait-for must be nil or a seq with a subset of #{:output :app-model}")
  (let [timeout (or timeout 1000)
        script (conj (vec (butlast script)) (with-meta (last script) {::last true}))
        record-states (atom [@(:state app)])]
    (add-watch (:state app) :state-watch
               (fn [_ _ _ n]
                 (swap! record-states conj n)))
    ;; Run begin messages
    (cond (= begin :default) (app/begin app)
          (vector? begin) (app/begin app begin))
    ;; Run script
    (app/run! app script)
    ;; Wait for all messages to be processed
    (loop [tout timeout]
      (if (pos? tout)
        (when (not= (meta (-> app :state deref :input)) {::last true})
          (do (Thread/sleep 20)
              (recur (- tout 20))))
        (throw (Exception. (str "Test timeout after " timeout "ms.")))))
    ;; Wait for specified queues to be consumed
    (if (seq wait-for)
      (doseq [k wait-for]
        (loop [queue (:queue @(.state (k app)))
               c 0]
          (when (> c 3)
            (throw (Exception. (str "The queue " k " is not being consumed."))))
          (when-not (zero? (count queue))
            (Thread/sleep 20)
            (let [new-queue (:queue @(.state (k app)))]
              (recur new-queue
                     (if (= new-queue queue)
                       (inc c)
                       0)))))))
    @record-states))
