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
  (:require [io.pedestal.http.route.sawtooth.impl :as impl]
            [io.pedestal.http.route.router :as router]))

(defn- -find-route [matcher request]
  (when-let [[route params] (matcher request)]
    ;; This is an ugly part of the Router protocol, that there
    ;; isn't a way to return the route and the path-params separately.
    ;; Inside io.pedestal.http.route/route-context, the :path-params
    ;; are assoc'ed into the request map.  This also means that the
    ;; route no longer follows its spec.
    (assoc route :path-parms params)))

(defn router
  [routes]
  (let [matcher (impl/create-matcher-from-routes routes)]
    (reify router/Router
      (find-route [_ request]
        (-find-route matcher request)))))