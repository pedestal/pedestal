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

(ns io.pedestal.http.route.definition.terse
  (:require [io.pedestal.http.route.definition :as route.definition]
            [io.pedestal.http.route.definition.verbose :as verbose]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]))

(defn- unexpected-vector-in-route [spec]
  (format "The route specification probably has too many levels of nested vectors: %s" spec))

(defn- unmatched-type-in-constraint [spec]
  (format "Cannot expand '%s' as a route. Expected a verb map or path string, but found a %s instead" spec (type spec)))

(defn- invalid-handler [handler original]
  (format "While parsing a verb map, found a %s as a handler. It must be a symbol that resolves to an interceptor or an actual interceptor. The full vector is %s" (type handler) original))

(defn- missing-handler [handler original]
  (format "When parsing a verb map, tried in vain to find a handler (as the first symbol that resolves to an interceptor or interceptor map in a vector). Looking in %s" vector))

(defn- leftover-declarations [vector original]
  (format "This vector for the verb map has extra elements. The leftover elements are %s from the original data %s" vector original))

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

(defmethod expand-constraint clojure.lang.PersistentVector [spec]
  (assert false (unexpected-vector-in-route spec)))

(defmethod expand-constraint :default [unmatched]
  (assert false (unmatched-type-in-constraint unmatched)))

(defprotocol ExpandableVerbAction
  (expand-verb-action [expandable-verb-action]
    "Expand `expandable-verb-action` into a verbose-form verb-map."))

(def valid-handler?      (some-fn seq? symbol? interceptor/interceptor?))
(def interceptor-vector? (every-pred vector? (comp :interceptors meta)))
(def constraint-map?     (every-pred map?    (comp :constraints  meta)))

(extend-protocol ExpandableVerbAction
  clojure.lang.Symbol
  (expand-verb-action [symbol] symbol)

  clojure.lang.IPersistentList
  (expand-verb-action [l] (expand-verb-action (eval l)))

  clojure.lang.APersistentVector
  (expand-verb-action [vector]
    ;; Take this apart by hand so we can provide nice error
    ;; messages. Exceptions from destructuring are opaque to users.
    (let [original     vector
          route-name   (when (keyword? (first vector)) (first vector))
          vector       (if   (keyword? (first vector)) (next vector) vector)
          interceptors (vec (apply concat (filter interceptor-vector? vector)))
          vector       (remove interceptor-vector? vector)
          handler      (first vector)
          vector       (next vector)
          _            (assert (valid-handler? handler) (invalid-handler handler original))]
      (assert handler (missing-handler handler original))
      (assert (empty? vector) (leftover-declarations vector original))
      {:route-name   route-name
       :handler      handler
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
  (let [vectors      (filter vector? specs)
        maps         (filter map? specs)
        children     (filter (comp not :interceptors meta) vectors)
        interceptors (filter interceptor-vector? specs)
        verbs        (reduce merge {} (filter (comp not :constraints meta) maps))
        constraints  (reduce merge {} (filter constraint-map? specs))]
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
  (filter vector? route-domain))

(defn- add-children
  "Add the :children key to verbose-map from route-domain, if appropriate."
  [route-domain verbose-map]
  (if-let [children (extract-children route-domain)]
    (assoc verbose-map :children (map expand-constraint children))
    verbose-map))

(defn first-of [p coll] (first (filter p coll)))

(defn- extract-port
  "Return the port, if present, from route-domain."
  [route-domain]
  (first-of number? route-domain))

(defn- extract-host
  "Return the host, if present, from route-domain."
  [route-domain]
  (first-of string? route-domain))

(defn- extract-scheme
  "Return the scheme, if present, from route-domain."
  [route-domain]
  (first-of #(and (keyword? %) (route.definition/schemes %)) route-domain))

(defn- extract-app-name
  "Return the app name, if present, from route-domain."
  [route-domain]
  (first-of #(and (keyword? %) (not (route.definition/schemes %))) route-domain))

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

(defn dissoc-when
  "Dissoc those keys from m whose values in m satisfy pred."
  [pred m]
  (apply dissoc m (filter #(pred (m %)) (keys m))))

(def preamble? (some-fn number? string? keyword?))

(defn flatten-terse-app-routes
  "Return a vector of maps that are equivalent to the terse routing syntax, but
   expanded for consumption by the verbose route parser."
  [route-spec]
  (let [[preamble routes] (split-with preamble? route-spec)]
    (assert (count routes) "There should be at least one route in the application vector")
    (log/debug :app-name (extract-app-name preamble) :route-count (count routes))
    (->> {:app-name (extract-app-name preamble)
          :host     (extract-host     preamble)
          :scheme   (extract-scheme   preamble)
          :port     (extract-port     preamble)}
         (dissoc-when nil?)
         (add-children routes))))

(defn terse-routes [route-spec]
  (verbose/expand-verbose-routes (map flatten-terse-app-routes route-spec)))

