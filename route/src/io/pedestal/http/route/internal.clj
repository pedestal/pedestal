; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.internal
  "Internal utilities, not for reuse, subject to change at any time."
  {:no-doc true
   :added  "0.7.0"}
  (:require [clj-commons.format.table :as t]
            [io.pedestal.http.route.types :as types]))

(defn- uniform?
  "Are all values of the projection of k onto coll the same?"
  [k coll]
  (->> coll
       (map k)
       distinct
       count
       (>= 1)))

;; This is used to let expanded-routes identify that the routes have already been expanded
;; and verified as a routing table.
(defrecord RoutingTable [routes])

(defn is-routing-table?
  [routing-table]
  (instance? RoutingTable routing-table))

(defn extract-routes
  "This eases the upgrade from 0.7 to 0.8, where some earlier code may pass the routing table
  directly to a router; we can now roll with the punches if we get a routing table,
  and properly signal an error if we get something unexpanded."
  [routes]
  (cond
    (is-routing-table? routes)
    (:routes routes)

    (satisfies? types/RoutingFragment routes)
    (throw (ex-info "Must pass route fragment through io.pedestal.http.route/expand-routes"
                    {:value routes}))

    (sequential? routes)
    routes

    :else
    (throw (ex-info "Expected routes, or a routing table"
                    {:value routes}))))

(defn print-routing-table
  [routing-table]
  ;; Omit the first few columns which may be missing, or may be entirely uniform
  (let [routes  (extract-routes routing-table)
        columns [(when-not (uniform? :app-name routes)
                   :app-name)
                 (when-not (uniform? :scheme routes)
                   :scheme)
                 (when-not (uniform? :host routes)
                   :host)
                 (when-not (uniform? :port routes)
                   :port)
                 :method
                 :path
                 {:key   :route-name
                  :title "Name"}]]
    (t/print-table (remove nil? columns)
                   (sort-by :path routes))))


(defn- print-routing-table-with-header
  [routing-table]
  (println "Routing table:")
  (print-routing-table routing-table))

(defn- wrap-routing-table-fn
  [routing-table-fn]
  (let [*prior-routes (atom nil)
        wrapped-fn    (fn []
                        (let [new-routing-table (routing-table-fn)
                              new-routes        (->> new-routing-table
                                                     :routes
                                                     ;; Ignore keys that aren't needed (and cause comparison problems).
                                                     (map #(select-keys % [:app-name :scheme :host :port :method :path :route-name]))
                                                     set)]
                          (when (not= new-routes @*prior-routes)
                            (print-routing-table-with-header new-routing-table)
                            (reset! *prior-routes new-routes))
                          new-routing-table))]
    ;; Execute once now to get the routing table displayed at startup.
    (wrapped-fn)
    ;; And return it to be used when building a routing interceptor
    wrapped-fn))

(defn wrap-routing-table
  "Wraps a routing table such that the table is output (at startup, and when changed if routing-table is actually a function)."
  [routing-table]
  (if (fn? routing-table)
    (wrap-routing-table-fn routing-table)
    (do
      (print-routing-table-with-header routing-table)
      routing-table)))

(defn create-routes-from-fn
  "Core of the route/routes-from macro."
  [route-spec-exprs env expand-routes]
  (let [exprs (map (fn [expr]
                     (if (and (symbol? expr)
                              (not (contains? env expr)))
                       `(deref (var ~expr))
                       expr))
                   route-spec-exprs)]
    `(fn []
       (~expand-routes ~@exprs))))

(defn- satisfies-query-constraints
  "Given a map of query constraints, return a predicate function of
  the request which will return true if the request satisfies the
  constraints."
  [query-constraints]
  (fn [request]
    (let [{:keys [query-params]} request]
      (every? (fn [[k re]]
                (when-let [v (get query-params k)]
                  (re-matches re v)))
              query-constraints))))

(defn- satisfies-path-constraints
  "Given a map of path constraints, return a predicate function of
  the request which will return true if the request satisfies the
  constraints."
  [path-constraints]
  (let [path-constraints (zipmap (keys path-constraints)
                                 (mapv re-pattern (vals path-constraints)))]
    (fn [path-param-values]
      (every? (fn [[k re]]
                (when-let [v (get path-param-values k)]
                  (re-matches re v)))
              path-constraints))))

(defn add-satisfies-constraints?
  "Given a route, add a function of the request which returns true if
  the request satisfies all path and query constraints."
  {:added "0.8.0"}
  [{:keys [query-constraints path-constraints] :as route}]
  (let [qc? (satisfies-query-constraints query-constraints)
        pc? (satisfies-path-constraints path-constraints)
        satisfies-constraints? (cond (and query-constraints path-constraints)
                                     (fn [request path-param-values]
                                       (and (qc? request) (pc? path-param-values)))
                                     query-constraints
                                     (fn [request _]
                                       (qc? request))
                                     path-constraints
                                     (fn [_ path-param-values]
                                       (pc? path-param-values))
                                     :else
                                     (fn [_ _] true))]
    (assoc route ::satisfies-constraints? satisfies-constraints?)))

(defn satisfies-constraints?
  "Used at the end of routing to see if the selected route's query constraints, if any,
  are satisfied."
  {:added "0.8.0"}
  [request route path-param-values]
  (let [f (::satisfies-constraints? route)]
    (f request path-param-values)))

