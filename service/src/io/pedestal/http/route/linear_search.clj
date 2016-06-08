; Copyright 2013 Relevance, Inc.
; Copyright 2014-2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.linear-search
  (:require [io.pedestal.http.route.router :as router]))

(defn- path-matcher [route]
  (let [{:keys [path-re path-params]} route]
    (fn [req]
      (when req
       (when-let [m (re-matches path-re (:path-info req))]
         (zipmap path-params (rest m)))))))

(defn- matcher-components [route]
  (let [{:keys [method scheme host port path query-constraints]} route]
    (list (when (and method (not= method :any)) #(= method (:request-method %)))
          (when host   #(= host (:server-name %)))
          (when port   #(= port (:server-port %)))
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
        base-match (if (seq base-matchers)
                     (apply every-pred base-matchers)
                     (constantly true))
        path-match (path-matcher route)]
    (fn [request]
      (and (base-match request) (path-match request)))))

(defn router
  "Given a sequence of routes, return a router which satisfies the
  io.pedestal.http.route.router/Router protocol."
  [routes]
  (let [matcher-routes (mapv #(assoc % :matcher (matcher %)) routes)]
    (reify
      router/Router
      (find-route [this request]
        (some (fn [{:keys [matcher] :as route}]
                (when-let [path-params (matcher request)]
                  (assoc route :path-params path-params)))
              matcher-routes)))))
