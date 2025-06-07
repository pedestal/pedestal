; Copyright 2024-2025 Nubank NA
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

(ns io.pedestal.http.route.definition
  "Constants and utilities used when implementing a routing specification, routing definition,
  or router.")

(def schemes #{:http :https})

(defn- uniquely-add-route-path
  "Append `route-path` to `route-paths` if route-paths doesn't contain it
  already."
  [route-paths route-path]
  (if (some #{route-path} route-paths)
    route-paths
    (conj route-paths route-path)))

(defn sort-by-constraints
  "Sort the grouping of route entries which all correspond to
  `route-path` from `groupings` such that the most constrained route
  table entries appear first and the least constrained appear last."
  [groupings route-path]
  (let [grouping (groupings route-path)]
    (sort-by (comp - count :query-constraints) grouping)))

(defn prioritize-constraints
  "Sort a flat routing table of entries to guarantee that the most
  constrained route entries appear in the table prior to entries which
  have fewer constraints or no constraints."
  [routing-table]
  (let [route-paths (map #(map % [:app-name :scheme :host :port :path-parts])
                                        routing-table)
        unique-route-paths (reduce uniquely-add-route-path [] route-paths)
        groupings (group-by #(map % [:app-name :scheme :host :port :path-parts])
                            routing-table)]
    (mapcat (partial sort-by-constraints groupings) unique-route-paths)))

(defn verify-unique-route-names
  [routing-table]
  (let [non-unique-names (->> routing-table
                              (group-by :route-name)
                              (map (fn [[k v]] [k (count v)]))
                              (filter (fn [[_ v]] (> v 1)))
                              (map first))]
    (when (seq non-unique-names)
      (throw (ex-info "Route names are not unique"
                      {:non-unique-names non-unique-names})))
    routing-table))



