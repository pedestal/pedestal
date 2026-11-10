; Copyright 2026 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.simple-bodies-test
  "A response :body that is a simple value (keyword, symbol, UUID, number, boolean, character)
  is written as EDN, and a ByteArrayOutputStream as its bytes, by every connector."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [io.pedestal.connector :as connector]
            [io.pedestal.connector.jdk-httpserver :as jdk-httpserver]
            [io.pedestal.connector.test :as test]
            [io.pedestal.http :as http]
            [io.pedestal.http.http-kit :as hk]
            [io.pedestal.http.impl.servlet-interceptor :as si]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.internal :as i]
            [io.pedestal.test :as servlet-test])
  (:import (java.io ByteArrayOutputStream)))

(def a-uuid #uuid "6a721905-7cb3-45f8-93cb-cab9c90b4100")

(defn- byte-array-output-stream
  ^ByteArrayOutputStream [^String s]
  (doto (ByteArrayOutputStream.)
    (.write (.getBytes s "UTF-8"))))

(def cases
  "Path, response body, expected body text, expected default content type."
  [["/keyword" :some-keyword ":some-keyword" "application/edn"]
   ["/qualified-keyword" ::reason ":io.pedestal.service.simple-bodies-test/reason" "application/edn"]
   ["/symbol" 'some-symbol "some-symbol" "application/edn"]
   ["/uuid" a-uuid "#uuid \"6a721905-7cb3-45f8-93cb-cab9c90b4100\"" "application/edn"]
   ["/long" 42 "42" "application/edn"]
   ["/double" 1.5 "1.5" "application/edn"]
   ["/big-decimal" 1.5M "1.5M" "application/edn"]
   ["/ratio" 1/3 "1/3" "application/edn"]
   ["/true" true "true" "application/edn"]
   ["/false" false "false" "application/edn"]
   ["/character" \c "\\c" "application/edn"]
   ["/byte-array-output-stream" (byte-array-output-stream "pdf-bytes") "pdf-bytes" "application/octet-stream"]])

(def routes
  (table/table-routes
    {}
    (concat
      (for [[path body] cases]
        [path :get (fn [_request] {:status 200 :headers {} :body body})
         :route-name (keyword "simple-body" (subs path 1))])
      ;; When the application sets the content type, only the writing of the body is involved.
      (for [[path body] cases]
        [(str "/explicit-content-type" path) :get (fn [_request]
                                                    {:status  200
                                                     :headers {"Content-Type" "text/plain"}
                                                     :body    body})
         :route-name (keyword "explicit-content-type" (subs path 1))]))))

(defn- content-type
  [response]
  (some (fn [[k v]]
          (when (= "content-type" (string/lower-case (name k)))
            v))
        (:headers response)))

(defn- check-responses
  [response-for]
  (doseq [[path _ expected-body expected-content-type] cases]
    (testing path
      (let [response (response-for path)]
        (is (= 200 (:status response)))
        (is (= expected-body (:body response)))
        (is (= expected-content-type (content-type response)))))
    (testing (str path " with an explicit content type")
      (let [response (response-for (str "/explicit-content-type" path))]
        (is (= 200 (:status response)))
        (is (= expected-body (:body response)))
        (is (= "text/plain" (content-type response)))))))

(defn- connector-map
  []
  (-> (connector/default-connector-map 0)
      (connector/with-default-interceptors)
      (connector/with-routes routes)))

(deftest servlet-writes-simple-bodies
  ;; The io.pedestal.http and io.pedestal.test path, as used by common-io
  (let [service-fn (i/with-deprecations-suppressed
                     (-> {::http/routes routes}
                         http/default-interceptors
                         http/service-fn
                         ::http/service-fn))]
    (check-responses #(i/with-deprecations-suppressed
                        (servlet-test/response-for service-fn :get % :timeout 1000)))))

(deftest jetty-connector-writes-simple-bodies
  (let [conn (jetty/create-connector (connector-map) nil)]
    (check-responses #(test/response-for conn :get %))))

(deftest http-kit-connector-writes-simple-bodies
  (let [conn (hk/create-connector (connector-map) nil)]
    (check-responses #(test/response-for conn :get %))))

(deftest jdk-httpserver-connector-writes-simple-bodies
  (let [conn (jdk-httpserver/create-connector (connector-map) nil)]
    (check-responses #(test/response-for conn :get %))))

(deftest write-body-to-stream-matches-pr
  (doseq [[_ body expected-body expected-content-type] cases]
    (let [output-stream (ByteArrayOutputStream.)]
      (si/write-body-to-stream body output-stream)
      (is (= expected-body (.toString output-stream "UTF-8")))
      (is (= expected-content-type (si/default-content-type body))))))
