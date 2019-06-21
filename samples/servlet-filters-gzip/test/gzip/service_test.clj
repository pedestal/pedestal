; Copyright 2014-2019 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns gzip.service-test
  (:require [clj-http.client :as http-cl]
            [clojure.test :refer :all]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as http]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.servlet :as servlet]
            [io.pedestal.http.impl.servlet-interceptor :as incept]
            [gzip.service :as service]
            [gzip.server :as server]))

(def service
  (::http/service-fn (http/create-servlet service/service)))

(defn jetty-server
  [app opts]
  (prn :jetty-server :enter)
  (let [options (assoc opts :join? false)
        service-fn (incept/http-interceptor-service-fn [app])
        jetty-server (servlet/servlet :service service-fn)]
    (prn :jetty-server :building)
    (jetty/server jetty-server opts)))

(defn get-response [addy]
  (try
    (http-cl/get addy)
    (catch clojure.lang.ExceptionInfo ex
      (prn "BOOM!\n\n")
      (prn ex)
      {:body "BOOM"})))

(deftest home-page-test
  (let [jetty (server/run-dev)
        response (get-response "http://localhost:8080")
        _ (http/stop jetty)]
    (testing "service response"
      (is (=
           (:body response)
           "Hello World!")))
    (testing "gzip-encoded"
      (is (.startsWith
           (:orig-content-encoding response)
           "gzip")))))
