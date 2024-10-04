; Copyright 2024 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.sawtooth
  {:added "0.8.0"}
  (:require [io.pedestal.http.route.internal :as internal]
            [io.pedestal.http.route.sawtooth.impl :as impl]))

(defn- find-route [matcher request]
  (when-let [[route path-params] (matcher request)]
    (when (internal/satisfies-constraints? request route path-params)
      ;; tests fail if path-params is nil
      [route (or path-params {})])))

(defn router
  [routes]
  (let [[matcher conflicts] (->> routes
                                 internal/extract-routes
                                 (mapv internal/add-satisfies-constraints?)
                                 impl/create-matcher-from-routes)]
    (when (seq conflicts)
      (impl/report-conflicts conflicts routes))
    (fn [request]
      (find-route matcher request))))
