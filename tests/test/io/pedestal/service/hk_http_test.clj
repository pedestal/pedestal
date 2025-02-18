; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.hk-http-test
  "Test Http-Kit connector using HTTP requests (to fully exercise async code paths)."
  (:require [io.pedestal.http.jetty :as jetty]
            [clojure.test :refer [deftest is use-fixtures]]
            [io.pedestal.http.response :as response]
            [io.pedestal.service.protocols :as p]
            [matcher-combinators.matchers :as m]
            [ring.util.response :refer [response]]
            [org.httpkit.client :as client]
            [clojure.core.async :refer [go]]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.test-common :as tc]
            [io.pedestal.service :as service]))

(defn hello-page
  [_request]
  (response "HELLO"))

(def async-hello
  (interceptor
    {:name  ::async-hello
     :enter (fn [context]
              (go
                (response/respond-with context 200 "ASYNC HELLO")))}))
(def routes
  (table/table-routes
    {}
    [["/hello" :get hello-page :route-name ::hello]
     ["/async/hello" :get async-hello]]))

(def port 38348)

(def base-url (str "http://localhost:" port))

(defn get!
  ([path]
   (get! path nil))
  ([path opts]
   @(client/get (str base-url path)
                (merge {:as :stream} opts))))

(defn new-connector
  []
  (-> (service/default-service-map port)
      (service/with-default-interceptors)
      (service/with-routing :sawtooth routes)
      (jetty/create-connector nil)))

(use-fixtures :once
              tc/instrument-specs-fixture
              (fn [f]
                (let [conn (new-connector)]
                  (try
                    (service/start! conn)
                    (f)
                    (finally
                      (service/stop! conn))))))

(deftest basic-access
  (is (match? {:status 200
               :body   (m/via slurp "HELLO")}
              (get! "/hello"))))


(deftest async-request-handling
  (is (match? {:status 200
               :body   (m/via slurp "ASYNC HELLO")}
              (get! "/async/hello"))))
