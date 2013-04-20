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

(defn run-sync!
  ([app script]
     (run-sync! app nil script 1000))
  ([app init script]
     (run-sync! app init script 1000))
  ([app init script timeout]
     (let [script (conj (vec (butlast script)) (with-meta (last script) {::last true}))
           record-states (atom [@(:state app)])]
       (add-watch (:state app) :state-watch
                  (fn [_ _ _ n]
                    (swap! record-states conj n)))
       (if init
         (app/begin app init)
         (app/begin app))
       (app/run! app script)
       (loop [timeout timeout]
         (if (pos? timeout)
           (if (= (meta (-> app :state deref :input)) {::last true})
             @record-states
             (do (Thread/sleep 20)
                 (recur (- timeout 20))))
           (throw (Exception. (str "Test timeout after " timeout "ms."))))))))

