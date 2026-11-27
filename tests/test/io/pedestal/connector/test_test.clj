; Copyright 2024-2026 Nubank NA
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

(ns io.pedestal.connector.test-test
  "Ported from io.pedestal.test-test (io.pedestal.test was removed in Pedestal 1.0): the URL given to
  io.pedestal.connector.test/response-for becomes the request's :scheme, :server-name, :server-port,
  :uri and :query-string.

  io.pedestal.test/parse-url was removed without replacement, so these tests check the request map
  instead of parse-url's result (the URL parsing is io.pedestal.service.impl/parse-url)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is are]]
            [io.pedestal.connector :as conn]
            [io.pedestal.connector.test :as test]
            [io.pedestal.http.jetty :as jetty]))

(defn ^:private echo-url
  [request]
  {:status 200
   :body   (select-keys request [:scheme :server-name :server-port :uri :query-string])})

(def connector
  (-> (conn/default-connector-map -1)
      (conn/with-interceptor echo-url)
      (jetty/create-connector nil)))

(defn ^:private request-for
  [url]
  (-> (test/response-for connector :get url)
      :body
      edn/read-string))

(deftest non-root-url
  (is (= "param=value"
         (-> "/foo?param=value"
             request-for
             :query-string))))

(deftest root-url-with-query-string
  (is (= "param=value"
         (-> "/?param=value"
             request-for
             :query-string))))

(deftest path-parsing-with-and-without-ports
  ;; Same URLs as before. A missing scheme is :http, a missing host is "localhost",
  ;; and the path is the :uri (with its leading slash).
  (are [url expected] (= expected (request-for url))
    "/" {:scheme       :http
         :server-name  "localhost"
         :server-port  -1
         :uri          "/"
         :query-string nil}
    "/foo" {:scheme       :http
            :server-name  "localhost"
            :server-port  -1
            :uri          "/foo"
            :query-string nil}
    "localhost/" {:scheme       :http
                  :server-name  "localhost"
                  :server-port  -1
                  :uri          "/"
                  :query-string nil}
    "localhost/foo" {:scheme       :http
                     :server-name  "localhost"
                     :server-port  -1
                     :uri          "/foo"
                     :query-string nil}
    "localhost:8080/" {:scheme       :http
                       :server-name  "localhost"
                       :server-port  8080
                       :uri          "/"
                       :query-string nil}
    "localhost:8080/foo" {:scheme       :http
                          :server-name  "localhost"
                          :server-port  8080
                          :uri          "/foo"
                          :query-string nil}
    "http://localhost:8080/foo" {:scheme       :http
                                 :server-name  "localhost"
                                 :server-port  8080
                                 :uri          "/foo"
                                 :query-string nil}
    "http://localhost:8080/foo?param=value" {:scheme       :http
                                             :server-name  "localhost"
                                             :server-port  8080
                                             :uri          "/foo"
                                             :query-string "param=value"}))
