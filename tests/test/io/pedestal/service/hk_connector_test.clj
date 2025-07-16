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
  "Tests when running Http-Kit using io.pedestal.connector.test/response-for (rather than HTTP)."
  (:require [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.core.async :refer [go]]
            [io.pedestal.http.response :refer [respond-with]]
            [io.pedestal.connector :as connector]
            [io.pedestal.http.http-kit :as hk]
            io.pedestal.http.http-kit.specs
            [matcher-combinators.matchers :as m]
            [ring.util.response :refer [response]]
            [io.pedestal.connector.test :as test]
            [io.pedestal.interceptor :refer [interceptor definterceptor]]
            [io.pedestal.http.route.definition.table :as table]))

(defn hello-page
  [_request]
  (response "HELLO"))

(def async-hello
  (interceptor
    {:name  ::async-hello
     :enter (fn [context]
              (go
                (respond-with context 200 "ASYNC HELLO")))}))

(def async-bytes
  (interceptor
    {:name  ::async-bytes
     :enter (fn [context]
              (go (respond-with context 200
                                         (.getBytes "ASYNC BYTES" "UTF-8"))))}))

(defn no-response
  [_request]
  nil)

(defn async-no-response
  [_request]
  (go nil))

(defn echo-headers
  [request]
  (response (:headers request)))

(defn echo-name
  [request]
  {:status 200
   :body   (str "Hello " (get-in request [:json-params :name]) "!")})

(definterceptor early []
  (enter [_ context]
         (respond-with context 200 "early response")))

(definterceptor late []
  (enter [_ context]
         (respond-with context 200 "late response")))

(def routes
  (table/table-routes
    {}
    [["/hello" :get hello-page :route-name ::hello]
     ["/hello" :post echo-name]
     ["/no-response" :get no-response :route-name ::no-response]
     ["/async/hello" :get async-hello]
     ["/async/bytes" :get async-bytes]
     ["/async/no-response" :get async-no-response]
     ["/echo/headers" :get echo-headers :route-name ::echo-headers]
     ["/early" :get [(->early) (->late)] :route-name ::early]]))

(def *connector (atom nil))

(defn new-connector
  []
  (-> (connector/default-connector-map 8080)
      (connector/with-default-interceptors)
      (connector/with-routes routes)
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
  (is (match? {:status  200
               :headers {:content-type "text/plain"}
               :body    "HELLO"}
              (response-for :get "/hello"))))

(deftest json-request-body
  (is (match? {:status 200
               :body   "Hello Mr. Client!"}
              (response-for :post "/hello"
                            :headers {:content-type "application/json"}
                            :body (json/write-json-str {:name "Mr. Client"})))))

(deftest chain-goes-async
  (is (match? {:status  200
               :headers {:content-type "text/plain"}
               :body    "ASYNC HELLO"}
              (response-for :get "/async/hello"))))

(deftest edn-response-body
  (is (match? {:status  200
               :headers {:content-type "application/edn"}
               :body    (m/via edn/read-string
                               {"my-key" "My-Value"})}
              (response-for :get "/echo/headers" :headers {:My-Key 'My-Value}))))

(deftest async-bytes-response
  (is (match? {:status  200
               :headers {:content-type "application/octet-stream"}
               :body    "ASYNC BYTES"}
              (response-for :get "/async/bytes"))))

(deftest status-404-if-no-response-sync
  (is (match? {:status 404
               :body   "Not Found"}
              (response-for :get "/no-response"))))

(deftest status-404-if-no-response-from-async-handler
  (is (match? {:status 404
               :body   "Not Found"}
              (response-for :get "/async/no-response"))))

(deftest early-return-when-response-added
  (is (match? {:status 200
               :body   "early response"}
              (response-for :get "/early"))))
