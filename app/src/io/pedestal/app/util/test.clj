(ns io.pedestal.app.util.test
  (:require [io.pedestal.app :as app]
            [io.pedestal.app.util.platform :as platform]))

(defn run-sync!
  ([app script]
     (run-sync! app script 1000))
  ([app script timeout]
     (app/run! app script)
     (loop [timeout timeout]
       (when (pos? timeout)
         (if (= (-> app :state deref :input) (last script))
           true
           (do (Thread/sleep 20)
               (recur (- timeout 20))))))))
