;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns ^:shared io.pedestal.app.util.observers)

(def ^:private listeners (atom {}))

(defn publish [event-type event]
  (doseq [f (get @listeners event-type)]
    (f event)))

(defn subscribe [event-type f]
  (swap! listeners update-in [event-type] (fnil conj []) f))

(defn clear []
  (reset! listeners {}))
