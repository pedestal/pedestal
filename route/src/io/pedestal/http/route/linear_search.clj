; Copyright 2024-2026 Nubank NA
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

(ns io.pedestal.http.route.linear-search
  (:require [io.pedestal.http.route.definition :as definition]
            [io.pedestal.http.route.internal :as internal]))

(defn- path-matcher [route]
  (let [{:keys [path-params path-re]} route]
    (fn [req]
      (when req
        (when-let [m (re-matches path-re (:path-info req))]
          (zipmap path-params (rest m)))))))

(defn- matcher-components [route]
  (let [{:keys [method scheme host port query-constraints]} route]
    (list (when (and method (not= method :any)) #(= method (:request-method %)))
          (when host #(= host (:server-name %)))
          (when port #(= port (:server-port %)))
          (when scheme #(= scheme (:scheme %)))
          (when query-constraints
            (fn [request]
              (let [params (:query-params request)]
                (every? (fn [[k re]]
                          (and (contains? params k)
                               (re-matches re (get params k))))
                        query-constraints)))))))

(defn- matcher [route]
  (let [base-matchers (remove nil? (matcher-components route))
        base-match    (if (seq base-matchers)
                        (apply every-pred base-matchers)
                        (constantly true))
        path-match    (path-matcher route)]
    (fn [request]
      (and (base-match request) (path-match request)))))

(defn router
  "Given a sequence of routes, return a router function.  Order is important for
  linear search, and unlike other routers, it will continue searching if it matches
  a route but doesn't match constraints for that route."
  [routes]
  (let [matcher-routes (->> routes
                            internal/extract-routes
                            definition/prioritize-constraints
                            (mapv #(assoc % ::matcher (matcher %))))]
    (fn [request]
      (some (fn [{::keys [matcher] :as route}]
              (when-let [path-params (matcher request)]
                [route path-params]))
            matcher-routes))))
