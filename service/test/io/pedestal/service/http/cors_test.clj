; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Integration tests of CORS processing."}
  io.pedestal.service.http.cors-test
  (:require [io.pedestal.service.http.route :as route]
            [io.pedestal.service.http.route.definition :refer [defroutes]]
            [io.pedestal.service.http.cors :as cors]
            [io.pedestal.service.interceptor :as interceptor :refer [defhandler]]
            [io.pedestal.service.http :as service]
            [ring.util.response :as ring-resp])
  (:use [clojure.test]
        [clojure.pprint]
        [io.pedestal.service.test]))

(defhandler hello-world
  [request] (ring-resp/response "Hello World!"))

(defroutes routes
  [[["/hello-world" {:get hello-world}]]])

(def app
  (::service/service-fn (-> {::service/routes routes
                             ::service/allowed-origins [#"foo.com"]}
                            service/default-interceptors
                            service/service-fn)))

(deftest no-origin-test
  (let [response (response-for app :get "/hello-world")]
    (is (= 200 (:status response)))
    (is (= nil (get-in response [:headers "Origin"])))))

(deftest good-origin-test
  (let [response (response-for app :get "/hello-world" :headers {"origin" "http://foo.com:8080"})]
    (is (= 200 (:status response)))
    (is (= "http://foo.com:8080" (get-in response [:headers "Access-Control-Allow-Origin"])))))

(deftest bad-origin-test
  (let [response (response-for app :get "/hello-world" :headers {"origin" "https://bar.org"})]
    (is (= 403 (:status response)))
    (is (= nil (get-in response [:headers "Origin"])))))
