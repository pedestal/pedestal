; Copyright 2024 Nubank NA
; Copyright 2016-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.map-tree
  (:require [io.pedestal.http.route.definition :as definition]
            [io.pedestal.http.route.internal :as route.internal]
            [io.pedestal.http.route.prefix-tree :as prefix-tree]
            [io.pedestal.http.route.internal :as internal]))

;; This router is optimized for applications with static routes only.
;; If your application contains path-params or wildcard routes,
;; the router falls back onto a prefix-tree, via the prefix-tree router.

;; This router places routes in a map, keyed by URI path.
;; A route path is matched only by map-lookup.
;; The value within the map is matching-route fn, matching on host, scheme,
;; and port.

(defn- find-route
  [tree-map req]
  ;; find a result in the prefix-tree - payload could contain multiple routes
  (when-let [match-fn (tree-map (:path-info req))]
    ;; call payload function to find specific match based on method, host, scheme and port
    (when-let [route (match-fn req)]
      ;; return a match only if query constraints are satisfied
      (when (internal/satisfies-constraints? req route nil) ;; the `nil` here is "path-params"
        [route nil]))))

(defn matching-route-map
  "Given the full sequence of route-maps,
  return a single map, keyed by path, whose value is a function matching on the req.
  The function takes a request, matches criteria and constraints, and returns
  the most specific match.
  This function only processes the routes if all routes are static."
  [routes]
  {:pre [(not (some prefix-tree/contains-wilds? (map :path routes)))]}
  (let [initial-tree-map (group-by :path
                                   (map internal/add-satisfies-constraints? routes))]
    (reduce (fn [tree [path related-routes]]
              (assoc tree path (prefix-tree/create-payload-fn related-routes)))
            {}
            initial-tree-map)))

(defn router
  "Given a sequence of routes, return a router function.

  This router is fast, because it uses a hash table lookup based entirely on path;
  this only works because none of the routes may have path parameters.

  If any of the routes do have path parameters, then [[prefix-tree/router]] is invoked
  to provide the router function."
  [routes]
  (route.internal/ensure-expanded-routes routes)
  (if (some prefix-tree/contains-wilds? (map :path routes))
    (prefix-tree/router routes)
    (let [routes' (definition/prioritize-constraints routes)
          tree-map (matching-route-map routes')]
      (fn [request]
        (find-route tree-map request)))))

