; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.definition.verbose
  (:require [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :refer [interceptor?]]
            [io.pedestal.interceptor.helpers :as interceptor]))

(defn handler-interceptor
  [handler name]
  (cond
    (interceptor? handler) (let [{interceptor-name :name :as interceptor} handler]
                             (assoc interceptor :name (or interceptor-name name)))
    (fn? handler) (interceptor/handler name handler)))


(defn resolve-interceptor [interceptor name]
  (if (interceptor? interceptor)
    (handler-interceptor interceptor name)
    (handler-interceptor (io.pedestal.interceptor/interceptor interceptor) name)

   ;(symbol? interceptor) (if (-> (resolve interceptor)
   ;                              fn?)
   ;                        (handler-interceptor ((resolve interceptor)) name)
   ;                        (handler-interceptor @(resolve interceptor) name))
   ;(seq? interceptor) (handler-interceptor (eval interceptor) name)

   ))

(defn symbol->keyword
  [s]
  (let [resolved (resolve s)
        {ns :ns n :name} (meta resolved)]
    (if resolved
      (keyword (name (ns-name ns)) (name n))
      (throw (ex-info "Could not resolve symbol" {:symbol s})))))

(defn handler-map [m]
  (cond
   (symbol? m)
   (let [handler-name (symbol->keyword m)]
     {:route-name handler-name
      :handler (resolve-interceptor m handler-name)})
   ;(isa? (type m) clojure.lang.APersistentMap)
   (instance? clojure.lang.IPersistentMap m)
   (let [{:keys [route-name handler interceptors]} m
         handler-name (cond
                       (symbol? handler) (symbol->keyword handler)
                       (interceptor? handler) (:name handler))
         interceptor (resolve-interceptor handler (or route-name handler-name))
         interceptor-name (:name interceptor)]
     {:route-name (if route-name
                    route-name
                    (if interceptor-name
                      interceptor-name
                      (throw (ex-info "Handler was not symbol or interceptor with name, no route name provided"
                                      {:handler-spec m}))))
      :handler (resolve-interceptor handler (or route-name handler-name))
      :interceptors (mapv #(resolve-interceptor % nil) interceptors)})))

(defn- add-terminal-info
  "Merge in data from `handler-map` to `start-terminal`"
  [{:keys [interceptors] :as start-terminal}
   {new-interceptors :interceptors :or {new-interceptors []} :as handler-map}]
  (merge start-terminal
         {:interceptors (-> interceptors
                            (into new-interceptors)
                            (conj (:handler handler-map)))
          :route-name (:route-name handler-map)}))

(defn- generate-verb-terminal
  "Return a new route table entry based on `dna` `path`, and a vector
  of `[verb handler]` pairs."
  [dna [verb handler]]
  (-> dna
      (merge {:method verb})
      (route/merge-path-regex)
      (merge dna)
      (add-terminal-info (handler-map handler))))

(defn- capture-constraint
  "Add parenthesis to a regex in order to capture its value during evaluation."
  [[k v]] [k (str "(" v ")")])

(defn- update-constraints
  "Return a new DNA based on the contents of `dna` and
  `constraints`. Constraints are added to path-constraints if no verbs
  are defined in the current DNA, and are sorted and added to
  path-constraints and query-constraints, depending on whether the
  constraint's key identifies a path-param."
  [{path-params :path-params :as dna} constraints verbs]
  (if (empty? verbs)
    (update-in dna [:path-constraints] merge (map capture-constraint constraints))
    (let [path-param? (fn [[k _]] (some #{k} path-params))
          [path-constraints query-constraints] ((juxt filter remove) path-param? constraints)]
      (-> dna
          (update-in [:path-constraints] merge (into {} (map capture-constraint path-constraints)))
          (update-in [:query-constraints] merge query-constraints)))))


(defn- update-dna
  "Return new DNA based on the contents of `parent-dna` and
  `current-node`"
  [{^String parent-path :path :as parent-dna}
   {:keys [constraints verbs interceptors path] :as current-node}]
  (cond-> parent-dna
          true (merge (select-keys current-node [:app-name :scheme :host :port]))
          path (route/parse-path path)
          ;; special case case where parent-path is "/" so we don't have double "//"
          path (assoc :path (str (if (and parent-path (.endsWith parent-path "/"))
                                   (subs parent-path 0 (dec (count parent-path))) parent-path)
                                 path))
          constraints (update-constraints constraints verbs)
          interceptors (update-in [:interceptors]
                                  into
                                  (map #(resolve-interceptor % nil) interceptors))))


(defn- generate-route-entries
  "Return a list of route table entries based on the treeish structure
  of `route-map` and `dna`"
  [dna {:keys [path verbs children] :as route-map}]
  (let [current-dna (update-dna dna route-map)]
    (concat (map (partial generate-verb-terminal current-dna) verbs)
            (mapcat (partial generate-route-entries current-dna) children))))

(defn- uniquely-add-route-path
  "Append `route-path` to `route-paths` if route-paths doesn't contain it
  already."
  [route-paths route-path]
  (if (some #{route-path} route-paths)
    route-paths
    (conj route-paths route-path)))

(defn- sort-by-constraints
  "Sort the grouping of route entries which all correspond to
  `route-path` from `groupings` such that the most constrained route
  table entries appear first and the least constrained appear last."
  [groupings route-path]
  (let [grouping (groupings route-path)]
    (sort-by (comp - count :query-constraints) grouping)))

(defn- prioritize-constraints
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

(def default-dna
  {:path-parts []
   :path-params []
   :interceptors []})

(defn- verify-unique-route-names
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

(defn expand-verbose-routes
  "Expand route-maps into a routing table of route entries."
  [route-maps]
  (->> route-maps
       (mapcat (partial generate-route-entries default-dna))
       prioritize-constraints
       verify-unique-route-names))

(defmacro defroutes
  "Define a routing table from a collection of route maps."
  [name route-maps]
  `(def ~name (quote ~(expand-verbose-routes route-maps))))

