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
  (:require [clj-commons.format.table :as t])
  (:import (clojure.lang Fn Sequential)))

(defn- uniform?
  "Are all values of the projection of k onto coll the same?"
  [k coll]
  (->> coll
       (map k)
       distinct
       count
       (>= 1)))

(defn print-routing-table
  [routes]
  ;; Omit the first few columns which may be missing, or may be entirely uniform
  (let [columns [(when-not (uniform? :app-name routes)
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

(defprotocol RoutingTableDevMode

  "Wraps a routing table (or a function that returns a routing table)
  in development mode to print out the table at startup, and when it
  changes in a visible way."

  (wrap-routing-table [routing-table]))


(extend-protocol RoutingTableDevMode

  Sequential
  (wrap-routing-table [routing-table]
    (print-routing-table-with-header routing-table)
    routing-table)

  Fn
  (wrap-routing-table [routing-table-fn]
    (let [*prior-routes (atom nil)
          wrapped-fn    (fn []
                          (let [new-routes  (routing-table-fn)
                                new-routes' (->> new-routes
                                                 ;; Ignore keys that aren't needed (and cause comparison problems).
                                                 (map #(select-keys % [:app-name :scheme :host :port :method :path :route-name]))
                                                 set)]
                            (when (not= new-routes' @*prior-routes)
                              (print-routing-table-with-header new-routes')
                              (reset! *prior-routes new-routes'))
                            new-routes))]
      ;; Execute once now to get the routing table displayed at startup.
      (wrapped-fn)
      ;; And return it to be used when building a Router
      wrapped-fn)))


(defn routes-from-expr
  "Core of the route/routes-from macro."
  [route-spec-expr env expand-routes]
  (if (and (symbol? route-spec-expr)
           (not (contains? env route-spec-expr)))
    `(fn []
       (->> (var ~route-spec-expr)
            deref
            ~expand-routes))
    ;; Either an inline route, a reference to a local symbol, or a function call.
    `(fn []
       (~expand-routes ~route-spec-expr))))
