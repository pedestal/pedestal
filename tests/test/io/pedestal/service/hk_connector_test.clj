; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.hk-connector-test
  "Tests when running Http-Kit using test-request (rather than HTTP)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.core.async :refer [go]]
            [io.pedestal.http.response :as response]
            [io.pedestal.service :as service]
            [io.pedestal.http.http-kit :as hk]
            [matcher-combinators.matchers :as m]
            [ring.util.response :refer [response]]
            [io.pedestal.service.test :as test]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.route.definition.table :as table]))

(defn hello-page
  [_request]
  (response "HELLO"))

(def async-hello
  (interceptor
    {:name  ::async-hello
     :enter (fn [context]
              (go
                (response/respond-with context 200 "ASYNC HELLO")))}))

(defn echo-headers
  [request]
  #trace/result request
  (response (:headers request)))

(def routes
  (table/table-routes
    {}
    [["/hello" :get hello-page :route-name ::hello]
     ["/async/hello" :get async-hello]
     ["/echo/headers" :get echo-headers :route-name ::echo-headers]]))

(def *connector (atom nil))

(defn new-connector
  []
  (-> (service/default-service-map 8080)
      (service/with-default-interceptors)
      (service/with-routing :sawtooth routes)
      (hk/create-connector nil)))

(use-fixtures :once
              (fn [f]
                (try
                  (reset! *connector (new-connector))
                  (f)
                  (finally
                    (reset! *connector nil)))))

(defn response-for
  [request-method url & {:as options}]
  (test/response-for @*connector request-method url options))

(deftest basic-access
  (is (match? {:status 200
               :body   (m/via slurp "HELLO")}
              (response-for :get "/hello"))))

#_
(deftest chain-goes-async
  (is (match? {:status 200
               :body   (m/via slurp "ASYNC HELLO")}
              (response-for :get "/async/hello"))))

(deftest edn-response-body
  (is (match? {:status 200
               :body   (m/via #(-> % slurp edn/read-string)
                              {"My-Key" "My-Value"})}
              (response-for :get "/echo/headers" :headers {:My-Key 'My-Value}))))
