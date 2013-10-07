; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service-tools.dev-test
  (:require [clojure.test :refer :all ]
            [clojure.pprint :as p]
            [io.pedestal.service.http :as bootstrap]
            [io.pedestal.service.http.route :as route]
            [io.pedestal.service-tools.dev :refer :all ]))

(def fake-routes {} )

(def user-service {::bootstrap/interceptors [{:name ::route/router :fake true}]})

(deftest init-without-root-interceptors-test
  "Default interceptors are added when none are defined."
  (let [service (init {} #'fake-routes)
        interceptors (::bootstrap/interceptors service)]
    ;; interceptors are added
    (is (not (nil? interceptors)))
    ;; one of the default interceptors is present
    (is (some #(= ::route/router (:name %)) interceptors))))

(deftest init-with-root-interceptors-test
  "Default interceptors are not added and the router is replaced,
   when a user has defined root interceptors."
  (let [service (init user-service #'fake-routes)
        interceptors (::bootstrap/interceptors service)]
    ;; interceptors are still there
    (is (not (nil? interceptors)))
    ;; no new interceptors from the set of defaults are added
    (is (not-any? #(= ::route/query-params (:name %)) interceptors))
    ;; the router is still there
    (is (some #(= ::route/router (:name %)) interceptors))
    ;; the router is replaced
    (is (not-any? #(and (= ::route/router (:name %)) (true? (:fake %))) interceptors))))