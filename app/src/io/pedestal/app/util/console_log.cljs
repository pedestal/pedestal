;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.app.util.console-log)

(defn log-map [m]
  (let [d (assoc m :timestamp (.getTime (js/Date.)))]
    (.log js/console (pr-str d))))

(defn log [& args]
  (log-map (apply hash-map args)))
