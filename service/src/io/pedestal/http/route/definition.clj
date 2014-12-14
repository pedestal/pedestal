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

(ns io.pedestal.http.route.definition
  (:require [io.pedestal.http.route.definition.verbose :as verbose]
            [io.pedestal.interceptor :as interceptor]
            [clojure.set :as set]))

(def schemes #{:http :https})

(declare expand-path)
(declare expand-query-constraint)

(defmulti expand-constraint
  "Expand into additional nodes which reflect `constraints` and apply
  them to specs. "
  (fn [[constraint & specs]] (type constraint)))

(defmethod expand-constraint String [path-spec]
  (expand-path path-spec))

(defmethod expand-constraint clojure.lang.APersistentMap [query-constraint-spec]
  (expand-query-constraint query-constraint-spec))

(defprotocol ExpandableVerbAction
  (expand-verb-action [expandable-verb-action]
    "Expand `expandable-verb-action` into a verbose-form verb-map."))

(extend-protocol ExpandableVerbAction
  clojure.lang.Symbol
  (expand-verb-action [symbol] symbol)

  clojure.lang.APersistentVector
  (expand-verb-action [vector]
    (let [route-name (first (filter #(isa? (type %) clojure.lang.Keyword) vector))
          handler (or (first (filter seq? vector))
                      (first (filter symbol? vector))
                      (first (filter interceptor/interceptor? vector)))
          interceptors (vec (apply concat (filter #(and (vector? %)
                                                        (-> %
                                                            meta
                                                            :interceptors))
                                                  vector)))]
      {:route-name route-name
       :handler handler
       :interceptors interceptors}))

  io.pedestal.interceptor.Interceptor
  (expand-verb-action [interceptor]
    {:handler interceptor}))

(defn- expand-verbs
  "Expand tersely specified verb-map into a verbose verb-map."
  [verb-map]
  (into {}
        (map (fn [[k v]] [k (expand-verb-action v)])
             verb-map)))

(defn- expand-abstract-constraint
  "Expand all of the directives in specs, adding them to routing-tree-node."
  [routing-tree-node specs]
  (let [vectors (filter #(isa? (type %) clojure.lang.APersistentVector)
                         specs)
        maps (filter #(isa? (type %) clojure.lang.APersistentMap)
                     specs)
        children (filter (comp not :interceptors meta) vectors)
        interceptors (filter (comp :interceptors meta) vectors)
        verbs (reduce merge {} (filter (comp not :constraints meta) maps))
        constraints (reduce merge {} (filter (comp :constraints meta) maps))]
    (cond-> routing-tree-node
            (not (empty? verbs)) (assoc :verbs (expand-verbs verbs))
            (not (empty? constraints)) (assoc :constraints constraints)
            (not (empty? interceptors)) (assoc :interceptors (vec (apply concat interceptors)))
            (not (empty? children)) (assoc :children (map expand-constraint children)))))

(defn- expand-path
  "Expand a path node in the routing tree to a node specifying its
  path, constraints, verbs, and children."
  [[path & specs]]
  (expand-abstract-constraint {:path path} specs))

(defn- expand-query-constraint
  "Expand a query constraint node in the routing tree to a node
  specifying its constraints, verbs, and children."
  [specs]
  (expand-abstract-constraint {:constraints {} #_query-constraint} specs))

(defn- extract-children
  "Return the children, if present, from route-domain."
  [route-domain]
  (filter #(isa? (type %) clojure.lang.APersistentVector) route-domain))

(defn- add-children
  "Add the :children key to verbose-map from route-domain, if appropriate."
  [verbose-map route-domain]
  (if-let [children (extract-children route-domain)]
    (assoc verbose-map :children (map expand-constraint children))
    verbose-map))

(defn- extract-port
  "Return the port, if present, from route-domain."
  [route-domain]
  (first (filter #(isa? (type %) Long) route-domain)))

(defn- add-port
  "Add the :host key to verbose-map from route-domain, if appropriate."
  [verbose-map route-domain]
  (if-let [port (extract-port route-domain)]
    (assoc verbose-map :port port)
    verbose-map))

(defn- extract-host
  "Return the host, if present, from route-domain."
  [route-domain]
  (first (filter #(isa? (type %) String) route-domain)))

(defn- add-host
  "Add the :host key to verbose-map from route-domain, if appropriate."
  [verbose-map route-domain]
  (if-let [host (extract-host route-domain)]
    (assoc verbose-map :host host)
    verbose-map))

(defn- extract-scheme
  "Return the scheme, if present, from route-domain."
  [route-domain]
  (first (set/intersection (set (filter #(isa? (type %)
                                               clojure.lang.Keyword)
                                        route-domain))
                           schemes)))

(defn- add-scheme
  "Add the :scheme key to verbose-map from route-domain, if appropriate."
  [verbose-map route-domain]
  (if-let [scheme (extract-scheme route-domain)]
    (assoc verbose-map :scheme scheme)
    verbose-map))

(defn- extract-app-name
  "Return the app name, if present, from route-domain."
  [route-domain]
  (first (set/difference (set (filter #(isa? (type %)
                                             clojure.lang.Keyword)
                                      route-domain))
                         schemes)))

(defn- add-app-name
  "Add the :app-name key to verbose-map from route-domain, if appropriate."
  [verbose-map route-domain]
  (if-let [app-name (extract-app-name route-domain)]
    (assoc verbose-map :app-name app-name)
    verbose-map))

(defn- expand-terse-route-domain
  "Expand a top-level routing domain to a verbose-style
  map of route entries."
  [route-domain]
  (-> {}
      (add-app-name route-domain)
      (add-scheme route-domain)
      (add-host route-domain)
      (add-port route-domain)
      (add-children route-domain)))

(defn map-routes->vec-routes
  "Given a map-based route description,
  return Pedestal's terse, vector-based routes, with interceptors correctly setup.
  These generated routes can be consumed by `expand-routes`"
  [route-map]
  (reduce (fn [acc [k v :as route]]
            (let [verbs (select-keys v [:get :post :put :delete :any])
                  interceptors (:interceptors v)
                  constraints (:constraints v)
                  subroutes (map #(apply hash-map %) (select-keys v (filter string? (keys v))))
                  subroute-vecs (mapv map-routes->vec-routes subroutes)]
              (into acc (filter seq (into
                                      [k verbs
                                      (when (seq interceptors)
                                          (with-meta interceptors
                                                     {:interceptors true}))
                                      (when (seq constraints)
                                        (with-meta constraints
                                                   {:constraints true}))]
                                      subroute-vecs)))))
          [] route-map))

(defprotocol ExpandableRoutes
  (expand-routes [expandable-route-spec]
                 "Generate and return the routing table from a given expandable
                 form of the routing syntax."))

(extend-protocol ExpandableRoutes

  clojure.lang.APersistentVector
  (expand-routes [route-spec]
    (->> route-spec
       (map expand-terse-route-domain)
       verbose/expand-verbose-routes))

  clojure.lang.APersistentMap
  (expand-routes [route-spec]
    (expand-routes [[(map-routes->vec-routes route-spec)]])))

(defmacro defroutes
  "Define a routing table from the terse routing syntax."
  [name route-spec]
  `(def ~name (expand-routes (quote ~route-spec))))

