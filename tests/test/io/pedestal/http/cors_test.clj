; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Integration tests of CORS processing."}
  io.pedestal.http.cors-test
  (:require [io.pedestal.connector :as conn]
            [io.pedestal.connector.test :as test]
            [io.pedestal.http.jetty :as jetty]
            [ring.util.response :as ring-resp]
            [clojure.test :refer [deftest is]]))

(defn hello-world
  [_request]
  (ring-resp/response "Hello World!"))

(def routes
  [[["/hello-world" {:get   #'hello-world
                     :patch [:another-hello #'hello-world]}]]])

;; The default interceptors, with the :allowed-origins option.
(defn ^:private make-app
  [allowed-origins]
  (-> (conn/default-connector-map -1)
      (conn/with-default-interceptors :allowed-origins allowed-origins)
      (conn/with-routes routes)
      (jetty/create-connector nil)))

(defn ^:private response-for
  [connector verb url & {:as options}]
  (test/response-for connector verb url options))

(def app (make-app ["http://foo.com:8080"]))

(deftest no-origin-test
  (let [response (response-for app :get "/hello-world")]
    (is (= 200 (:status response)))
    (is (= nil (get-in response [:headers "Origin"])))))

(deftest good-origin-test
  (let [response (response-for app :get "/hello-world" :headers {"origin" "http://foo.com:8080"})]
    (is (= 200 (:status response)))
    (is (= "http://foo.com:8080" (get-in response [:headers "Access-Control-Allow-Origin"])))))

(deftest good-origin-patch-test
  (let [response (response-for app :patch "/hello-world" :headers {"origin" "http://foo.com:8080"})]
    (is (= 200 (:status response)))
    (is (= "http://foo.com:8080" (get-in response [:headers "Access-Control-Allow-Origin"])))))

(deftest bad-origin-test
  (let [response (response-for app :get "/hello-world" :headers {"origin" "https://bar.org"})]
    (is (= 403 (:status response)))
    (is (= nil (get-in response [:headers "Origin"])))))

(def allowed-origins-as-map-app
  (make-app {:allowed-origins ["http://foo.com:8080"]}))

(deftest allowed-origins-as-map-no-origin-test
  (let [response (response-for allowed-origins-as-map-app :get "/hello-world")]
    (is (= 200 (:status response)))
    (is (= nil (get-in response [:headers "Origin"])))))

(deftest allowed-origins-as-map-good-origin-test
  (let [response (response-for allowed-origins-as-map-app :get "/hello-world" :headers {"origin" "http://foo.com:8080"})]
    (is (= 200 (:status response)))
    (is (= "http://foo.com:8080" (get-in response [:headers "Access-Control-Allow-Origin"])))))

(deftest allowed-origins-as-map-good-origin-patch-test
  (let [response (response-for allowed-origins-as-map-app :patch "/hello-world" :headers {"origin" "http://foo.com:8080"})]
    (is (= 200 (:status response)))
    (is (= "http://foo.com:8080" (get-in response [:headers "Access-Control-Allow-Origin"])))))

(deftest allowed-origins-as-map-bad-origin-test
  (let [response (response-for allowed-origins-as-map-app :get "/hello-world" :headers {"origin" "https://bar.org"})]
    (is (= 403 (:status response)))
    (is (= nil (get-in response [:headers "Origin"])))))

