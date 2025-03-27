; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.jetty-connector-test
  "Tests when running Jetty as a connector, using test-request."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.core.async :refer [go]]
            [io.pedestal.http.response :as response]
            [io.pedestal.connector :as connector]
            [io.pedestal.http.jetty :as jetty]
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
  (-> (connector/default-connector-map 8080)
      (connector/with-default-interceptors)
      (connector/with-routing :sawtooth routes)
      (jetty/create-connector nil)))

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
  (is (match? {:status  200
               :headers {:content-type "text/plain"}
               :body    "HELLO"}
              (response-for :get "/hello"))))

(deftest includes-essential-security-headers
  (is (match? {:status  200
               :headers {:strict-transport-security         "max-age=31536000; includeSubdomains"
                         :x-frame-options                   "DENY"
                         :x-content-type-options            "nosniff"
                         :x-xss-protection                  "1; mode=block"
                         :x-download-options                "noopen"
                         :x-permitted-cross-domain-policies "none"
                         :content-security-policy           "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"}}
              (response-for :get "/hello"))))

(deftest chain-goes-async
  (is (match? {:status  200
               :headers {:content-type "text/plain"}
               :body    (m/via slurp "ASYNC HELLO")}
              (response-for :get "/async/hello"
                            :as :stream))))

(deftest edn-response-body
  (is (match? {:status  200
               :headers {:content-type "application/edn"}
               :body    (m/via edn/read-string
                               {"my-key" "My-Value"})}
              (response-for :get "/echo/headers" :headers {:My-Key 'My-Value}))))
