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

(ns io.pedestal.http.route.table
  (:require [io.pedestal.interceptor :as i]
            [io.pedestal.http.route :as route]))

(defn- syntax-error
  [{:keys [row original]} posn expected was]
  (format  "In row %d, %s should have been %s but was %s.\nThe whole route was was: %s"
           (inc row) posn expected (or was "nil") (or original "nil")))

(defn- surplus-declarations
  [{:keys [row original remaining]}]
  (format "In row %s, there were unused elements %s.\nThe input was: %s"
          (inc row) remaining original))

(def ^:private known-options [:app-name :host :port :scheme])
(def ^:private known-verb    #{:any :get :put :post :delete :patch :options :head})
(def ^:private default-port  {:http 80 :https 443})

(defn apply-defaults
  [{:keys [port scheme] :as opts}]
  (if-not port
    (assoc opts :port (default-port (or scheme :http)))
    opts))

(defn take-next-pair
  [argname expected-pred expected-str ctx]
  (let [[param arg & more] (:remaining ctx)]
    (if (= argname param)
      (do
        (assert (expected-pred arg) (syntax-error ctx (str "the parameter after" argname) expected-str arg))
        (assoc ctx argname arg :remaining more))
      ctx)))

(def parse-constraints (partial take-next-pair :constraints map? "a map"))
(def parse-route-name  (partial take-next-pair :route-name  keyword? "a keyword"))

(defn parse-path
  [ctx]
  (let [[path & more] (:remaining ctx)]
    (assert (string? path) (syntax-error ctx "the path (first element)" "a string" path))
    (assoc ctx :path path :remaining more)))

(defn parse-verb
  [ctx]
  (let [[verb & more] (:remaining ctx)]
    (assert (known-verb verb) (syntax-error ctx "the verb (second element)" (str "one of " known-verb) verb))
    (assoc ctx :method verb :remaining more)))

(defn parse-handlers
  [ctx]
  (let [[handlers & more] (:remaining ctx)]
    (if (vector? handlers)
      (assert (every? i/interceptor? handlers) (syntax-error ctx "the vector of handlers" "a bunch of interceptors" handlers))
      (assert (i/interceptor? handlers)        (syntax-error ctx "the handler" "an interceptor" handlers)))
    (assoc ctx :interceptors handlers :remaining more)))

(defn parse-context
  [opts row route]
  (assert (vector? route) (syntax-error row nil "the element" "a vector" route))
  (merge {:row       row
          :original  route
          :remaining route}
         (select-keys opts known-options)))

(defn finalize
  [ctx]
  (assert (empty? (:remaining ctx)) (surplus-declarations ctx))
  (select-keys ctx route/allowed-keys))

(defn route-table-row
  [opts row route]
  (-> opts
      apply-defaults
      (parse-context row route)
      parse-path
      parse-verb
      parse-handlers
      parse-route-name
      parse-constraints
      finalize))

(defn route-table
  [opts routes]
  {:pre [(map? opts) (sequential? routes)]}
  (map-indexed (partial route-table-row opts) routes))
