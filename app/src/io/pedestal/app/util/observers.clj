;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns ^:shared io.pedestal.app.util.observers)

(def ^:private listeners (atom {}))

(defn publish [topic message]
  (doseq [f (get @listeners topic)]
    (f message)))

(defn subscribe [topic f]
  (swap! listeners update-in [topic] (fnil conj []) f))

(defn clear []
  (reset! listeners {}))
