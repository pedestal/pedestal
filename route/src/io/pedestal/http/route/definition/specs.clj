; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.definition.specs
  "Clojure spec definitions related to routing descriptions and routing tables."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [io.pedestal.interceptor :as i])
  (:import (java.util.regex Pattern)))

(defn- is-re?
  [o]
  (instance? Pattern o))

(defn has-meta?
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
(s/def ::path string?)
(s/def ::verbs (s/map-of ::verb ::verb-action))
(s/def ::verb #{:any :get :post :put :delete})
(s/def ::verb-action (s/or
                       ;; A symbol here is resolved via IntoInterceptor
                       :symbol symbol?
                       :handler ::handler
                       :map ::handler-map))

(s/def ::handler-map (s/keys :req-un [::handler]
                             :opt-un [::route-name
                                      ::i/interceptors]))

(s/def ::route-name keyword?)
;; ::i/interceptor is anything that can be converted to an interceptor, including a function
;; (which is considered a handler function, and is wrapped into an interceptor).
(s/def ::handler ::i/interceptor)

(s/def ::constaints (s/map-of simple-keyword? ::constraint))
(s/def ::constraint is-re?)

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
         (s/cat
           :path-segment (s/? ::path-segment)
           :early-clarifications (s/* ::terse-clarification)
           ;; Sometimes, verbs omitted at a tree node, but handled at leaf
           :verbs (s/* ::terse-verbs)
           :late-clarifications (s/* ::terse-clarification)
           :routes (s/* ::terse-route-entry))))

(s/def ::path-segment
  (s/and string?
         #(string/starts-with? % "/")))

;; Broken out this way because some examples (in tests) have
;; constraints before the verb map.

(s/def ::terse-clarification
  (s/or :interceptors ::terse-interceptors
        :constraints ::terse-constraints))


(s/def ::terse-interceptors
  (s/and (has-meta? :interceptors)
         ::i/interceptors))

(s/def ::terse-constraints
  (s/and (has-meta? :constraints)
         ::constaints))

(s/def ::terse-verbs
  (s/map-of ::verb ::terse-verb-action))

(s/def ::terse-verb-action
  (s/or :simple ::i/interceptor
        :full ::full-terse-verb-action))

(s/def ::full-terse-verb-action
  (s/cat :route-name ::route-name
         ;; Sometimes, ^:interceptors [] comes between route name and the handler/interceptor.
         :interceptor (s/? ::terse-interceptors)
         :interceptor ::i/interceptor))


(comment
  (require '[expound.alpha :as expound])
  (s/conform ::terse-route-entry ["/root" ["/child" {:get [:foo-bar-route map]}]])
  (let [input [:my-app :http "example.org" 999 ["/foo" {}
                                                ^:interceptors []]]]
    (s/conform ::terse-routes
               input))

  )


;; --- EXPANDED ROUTING TABLE ---

(s/def ::routing-table (s/coll-of ::routing-entry))
(s/def ::routing-entry (s/keys
                         :req-un [::path
                                  ::method
                                  ::path-re
                                  ::path-parts
                                  ::host
                                  ::i/interceptors
                                  ::route-name
                                  ::path-params]))

(s/def ::path-re is-re?)
(s/def ::method ::verb)
(s/def ::path-parts (s/coll-of string?))
(s/def ::path-params (s/coll-of string?))                   ;; Maybe?
