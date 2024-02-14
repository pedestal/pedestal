; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^{:deprecated "0.7.0"} io.pedestal.service-tools.dev
  "Development utilities for Pedestal.

  Deprecated with no replacement; to be removed in a later release."
  (:require [ns-tracker.core :as tracker]
            [io.pedestal.internal :refer [deprecated]]))

(defn- ns-reload [track]
  (try
    (doseq [ns-sym (track)]
      (require ns-sym :reload))
    (catch Throwable e (.printStackTrace e))))

(defn watch
  "Watches a list of directories for file changes, reloading them as necessary."
  ([] (deprecated `watch (watch ["src"])))
  ([src-paths]
   (deprecated `watch
               (let [track (tracker/ns-tracker src-paths)
                     done  (atom false)]
                 (doto
                   (Thread. (fn []
                              (while (not @done)
                                (ns-reload track)
                                (Thread/sleep 500))))
                   (.setDaemon true)
                   (.start))
                 (fn [] (swap! done not))))))

(defn watch-routes-fn
  "Given a routes var and optionally a vector of paths to watch,
  return a function suitable for a service's :routes entry,
  that reloads routes on source file changes."
  ([routes-var]
   (deprecated `watch-routes-fn
     (watch-routes-fn routes-var ["src"])))
  ([routes-var src-paths]
   (deprecated `watch-routes-fn
     (let [tracked (tracker/ns-tracker src-paths)]
       (fn []
         (ns-reload tracked)
         (deref routes-var))))))

