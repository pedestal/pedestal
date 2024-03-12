; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.specs
  "Clojure spec definitions related to routing descriptions and routing tables.

  This namespace includes function specifications for a number of routing-related functions;
  specs are optional unless this namespace is required."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [io.pedestal.interceptor :as i]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.http.route.definition.terse :as terse]
            [io.pedestal.http.route.definition.verbose :as verbose]
            [io.pedestal.http.route :as route])
  (:import (java.util.regex Pattern)))

(defn- is-re?
  [o]
  (instance? Pattern o))

(defn- has-meta?
  [k]
  (fn [v]
    (contains? (meta v) k)))

;; --- VERBOSE ROUTES ---

(s/def ::verbose-routes (s/coll-of ::verbose-route))
(s/def ::verbose-route (s/keys :opt-un [::app-name
                                        ::scheme
                                        ::host
                                        ::children
                                        ::port
                                        ;; TODO: some of these should be sometimes required, or at least
                                        ;; tied together.
                                        ::path              ;; Probably required except at root
                                        ::verbs             ;; Ditto
                                        ::i/interceptors
                                        ]))

(s/def ::app-name keyword?)
(s/def ::host string?)
(s/def ::scheme #{:http :https})
(s/def ::children (s/coll-of ::verbose-route))
(s/def ::port (s/and integer? pos?))
(s/def ::path (s/and string?
                     #(string/starts-with? % "/")))
(s/def ::verbs (s/map-of ::verb ::verb-action))
(s/def ::verb #{:any :get :put :post :delete :path :options :head})
(s/def ::verb-action (s/or
                       :handler ::handler
                       :map ::handler-map))

(s/def ::handler-map (s/keys :req-un [::handler]
                             :opt-un [::route-name
                                      ::i/interceptors]))

(s/def ::route-name keyword?)
;; ::i/interceptor is anything that can be converted to an interceptor, including a function
;; (which is considered a handler function, and is wrapped into an interceptor).
(s/def ::handler ::i/interceptor)

(s/def ::constraints (s/map-of simple-keyword? ::constraint))
(s/def ::constraint is-re?)

;; --- TABLE ROUTES ---

(s/def ::table-options (s/keys
                         :opt-un [::app-name
                                  ::host
                                  ::port
                                  ::scheme
                                  :io.pedestal.http.route.definition.table/verbs]))

;; TODO: Can define optional allowed verbs in the options, which can include custom verbs,
;; and that should be applied as a constraint when defining verbs in individual routes.

(s/def :io.pedestal.http.route.definition.table/verbs (s/coll-of keyword?))

(s/def ::table-routes (s/coll-of ::table-route))

(s/def ::table-route
  (s/cat
    :path ::path
    :verb keyword?                                          ;; This should be constrained based on options
    :handler ::table-handler
    :clauses  (s/keys* :opt-un [::route-name ::constraints])))

(s/def ::table-handler
  (s/or
    :interceptor ::i/interceptor
    :interceptors ::i/interceptors))

;; --- TERSE ROUTES ---

(s/def ::terse-routes (s/coll-of ::terse-route))
(s/def ::terse-route
  (s/cat
    :preamble (s/* ::terse-route-preamble)
    :routes (s/+ ::terse-route-entry)))

(s/def ::terse-route-preamble
  (s/or :scheme ::scheme
        :app-name ::app-name
        :host ::host
        :port ::port))

(s/def ::terse-route-entry
  (s/and vector?
         (s/+
           ;; The use of alt allows for variations in ordering, which we see in the test data.
           ;; However, it also allows for multiples of the values (only :route should allow multiple).
           ;; If terse syntax was written today, I suspect it would be slightly more constrained so that
           ;; we could use s/cat here instead.
           (s/alt
             :path-segment ::path-segment
             :interceptors ::terse-interceptors
             :constraints ::terse-constraints
             :verbs ::terse-verbs
             :route ::terse-route-entry))))

(s/def ::path-segment
  (s/and string?
         #(string/starts-with? % "/")))

(s/def ::terse-interceptors
  (s/and (has-meta? :interceptors)
         ::i/interceptors))

(s/def ::terse-constraints
  (s/and (has-meta? :constraints)
         ::constraints))

(s/def ::terse-verbs
  (s/map-of ::verb ::terse-verb-action))

(s/def ::terse-verb-action
  (s/or :simple ::i/interceptor
        :full ::full-terse-verb-action))

(s/def ::full-terse-verb-action
  (s/cat :route-name ::route-name
         ;; Sometimes, ^:interceptors [] comes between route name and the handler/interceptor.
         :interceptors (s/? ::terse-interceptors)
         :interceptor ::i/interceptor))

;; --- EXPANDED ROUTING TABLE ---

(s/def ::routing-table (s/coll-of ::routing-entry))

(s/def ::routing-entry (s/keys
                         :req-un [::path
                                  ::method
                                  ::path-re
                                  ::path-parts
                                  ::interceptors
                                  ::route-name
                                  ::path-params
                                  ::path-constraints
                                  ::query-constraints]
                         :opt-un [::app-name
                                  ::scheme
                                  ::host
                                  ::port
                                  ::matcher]))

;; An RE that matches a path, and also defines capture groups for the :path-params
(s/def ::path-re is-re?)
(s/def ::method ::verb)
(s/def ::path-parts (s/coll-of ::path-part))
(s/def ::path-part (s/or :literal string?
                         :param keyword?))
;; The params defined in the path as keywords; used to build a map of keyword to path parameter
;; when matched.
(s/def ::path-params (s/coll-of keyword?))

;; In an expanded routing entry, the interceptors should also be expanded
;; (into Interceptor records).
(s/def ::interceptors (s/coll-of ::i/interceptor))

;; Constraints from the definition are split up; those that match a part parameter
;; go in :path-constraints, the rest go in :query-constraints.

(s/def ::path-constraints ::constraints)
(s/def ::query-constraints ::constraints)

;; --- FUNCTION SPECIFICATIONS ----

;; Tricky, because of the optional leading options map which can (instead)
;; be embedded in the routes map.

(s/fdef table/table-routes
        :args (s/or
                :informal (s/*
                            (s/or
                              :options ::table-options
                              :route ::table-route))
                :proper (s/cat
                          :options (s/? ::table-options)
                          :routes ::table-routes))
        :ret ::routing-table)

(s/fdef terse/terse-routes
        :args (s/cat :routes ::terse-routes)
        :ret ::routing-table)

(s/fdef verbose/expand-verbose-routes
        :args (s/cat :routes ::verbose-routes)
        :ret ::routing-table)

(s/def ::route-specification #(satisfies? route/ExpandableRoutes %))

(s/fdef route/expand-routes
        :args (s/cat :spec ::route-specification)
        :ret ::routing-table)
