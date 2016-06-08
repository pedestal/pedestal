; Copyright 2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.map-tree
  (:require [io.pedestal.http.route.prefix-tree :as prefix-tree]
            [io.pedestal.http.route.router :as router]))

;; This router is optimized for applications with static routes only.
;; If your application contains path-params or wildcard routes,
;; the router fallsback onto a prefix-tree, via the prefix-tree router.

;; This router places routes in a map, keyed by URI path.
;; A route path is matched only by map-lookup.
;; The value within the map is matching-route fn, matching on host, scheme,
;; and port.

(defrecord MapRouter [routes tree-map]
  router/Router
  (find-route [this req]
    ;; find a result in the prefix-tree - payload could contains mutiple routes
    (when-let [match-fn (tree-map (:path-info req))]
      ;; call payload function to find specific match based on method, host, scheme and port
      (when-let [route (match-fn req)]
        ;; return a match only if query constraints are satisfied
        (when ((::prefix-tree/satisfies-constraints? route) req nil) ;; the `nil` here is "path-params"
          route)))))

(defn matching-route-map
  "Given the full sequence of route-maps,
  return a single map, keyed by path, whose value is a function matching on the req.
  The function takes a request, matches criteria and constraints, and returns
  the most specific match.
  This function only processes the routes if all routes are static."
  [routes]
  {:pre [(not (some prefix-tree/contains-wilds? (map :path routes)))]}
  (let [initial-tree-map (group-by :path
                                   (map prefix-tree/add-satisfies-constraints? routes))]
    (reduce (fn [tree [path related-routes]]
              (assoc tree path (prefix-tree/create-payload-fn related-routes)))
            {}
            initial-tree-map)))

(defn router
  "Given a sequence of routes, return a router which satisfies the
  io.pedestal.http.route.router/Router protocol."
  [routes]
  (if (some prefix-tree/contains-wilds? (map :path routes))
    (prefix-tree/router routes)
    (->MapRouter routes (matching-route-map routes))))

(comment

  (def my-router (router [{:path "/foo"}
                          {:path "/foo/bar"}]))

  (let [req {:path-info "/foo"}
        route (((:tree-map my-router) "/foo") req)]
    ((::prefix-tree/satisfies-constraints? route) req nil))

  (:path (router/find-route my-router {:path-info "/foo"}))
  ;;=> "/foo"

  (:path (router/find-route my-router {:path-info "/foo/bar"}))
  ;;=> "/foo/bar"

  ;; When you use path-params, you'll get a prefix-tree
  (type (router [{:path "/foo"}
                 {:path "/foo/:bar"}]))

  )
