(ns gzip.service-test
  (:require [clj-http.client :as http]
            [clojure.test :refer :all]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as bootstrap]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.servlet :as servlet]
            [io.pedestal.http.impl.servlet-interceptor :as incept]
            [gzip.service :as service]
            [gzip.server :as server]))

(def service
  (::bootstrap/service-fn (bootstrap/create-servlet service/service)))

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
    (http/get addy)
    (catch clojure.lang.ExceptionInfo ex
      (prn "BOOM!\n\n")
      (prn ex)
      {:body "BOOM"})))

(deftest home-page-test
  (let [jetty (server/run-dev)
        response (get-response "http://localhost:8080")
        _ (bootstrap/stop jetty)]
    (testing "service response"
      (is (=
           (:body response)
           "Hello World!")))
    (testing "gzip-encoded"
      (is (.startsWith
           (:orig-content-encoding response)
           "gzip")))))


