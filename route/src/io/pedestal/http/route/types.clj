; Copyright 2024 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.types
  "Home for protocols related to routes (that aren't elsewhere for historical reasons)."
  {:added "0.8.0"})

;; A RoutingFragment is a collection of routes produced from a RouteSpecification or
;; via explicit calls to a route definition; these fragments are combined, validated,
;; and eventually passed to a Router constructor.
(defprotocol RoutingFragment

  (fragment-routes [this]
    "Returns a seq of route maps."))

(defrecord RoutingFragmentImpl
  [routes]

  RoutingFragment
  (fragment-routes [_] routes))

(defn fragment
  [routes]
  ;; The vec is necessary for specific tests to pass, otherwise lazy evaluation
  ;; gets in the way and AssertionError is not thrown until too late. It does
  ;; no harm.
  (->RoutingFragmentImpl (vec routes)))

