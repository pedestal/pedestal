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
  (:require [clojure.core.async :refer [go]]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [io.pedestal.connector :as connector]
            [io.pedestal.connector.test :as test]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.response :as response]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.interceptor :refer [interceptor]]
            [matcher-combinators.matchers :as m]
            [ring.util.response :refer [response]])
  (:import (java.io Writer)
           (org.eclipse.jetty.ee10.servlet ServletApiResponse)))

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
      (connector/with-routes routes)
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
               :headers {"Content-Type" "text/plain"}
               :body    "HELLO"}
              (response-for :get "/hello"))))

(deftest includes-essential-security-headers
  (is (match? {:status  200
               :headers {"Strict-Transport-Security"         "max-age=31536000; includeSubdomains"
                         "X-Frame-Options"                   "DENY"
                         "X-Content-Type-Options"            "nosniff"
                         "X-XSS-Protection"                  "1; mode=block"
                         "X-Download-Options"                "noopen"
                         "X-Permitted-Cross-Domain-Policies" "none"
                         "Content-Security-Policy"           "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"}}
              (response-for :get "/hello"))))

(deftest chain-goes-async
  (is (match? {:status  200
               :headers {"Content-Type" "text/plain"}
               :body    (m/via slurp "ASYNC HELLO")}
              (response-for :get "/async/hello"
                            :as :stream))))

(deftest edn-response-body
  (is (match? {:status  200
               :headers {"Content-Type" "application/edn"}
               :body    (m/via edn/read-string
                               {"My-Key" "My-Value"})}
              (response-for :get "/echo/headers" :headers {:My-Key 'My-Value}))))

;; A possible solution:
#_(defmethod print-method ServletApiResponse
    ;; Follows the same standard as
    ;; https://github.com/clojure/clojure/blob/clojure-1.12.3/src/clj/clojure/core_print.clj#L104-L115
    ;; Results in:
    #_#object[org.eclipse.jetty.ee10.servlet.ServletApiResponse
              0x00000000
              "org.eclipse.jetty.ee10.servlet.ServletApiResponse@0x00000000"]
    [this ^Writer w]
    (let [class-name (-> this class .getName)
          id (System/identityHashCode this)]
      (.write w "#object [")
      (.write w class-name)
      (.write w " ")
      (.write w (format "0x%x " id))
      (print-method (format "%s@0x%x" class-name id) w)
      (.write w "]")))


(deftest context-scope-capture
  (let [*ctx (promise)
        conn (-> (connector/default-connector-map 8888)
               (connector/with-default-interceptors)
               (connector/with-routes #{["/" :get {:enter (fn [ctx]
                                                            (deliver *ctx ctx)
                                                            (assoc ctx :response {:status 204}))}
                                         :route-name :context-scope-capture]})
               (jetty/create-connector nil)
               (connector/start!))]
    (try
      (slurp "http://localhost:8888")
      (finally
        (connector/stop! conn)))
    (is (= :ok

          ;; (class servlet-response) => org.eclipse.jetty.ee10.servlet.ServletApiResponse


          ;; ServletApiResponse implements .toString here
          ;; https://github.com/jetty/jetty.project/blob/jetty-12.0.29/jetty-ee10/jetty-ee10-servlet/src/main/java/org/eclipse/jetty/ee10/servlet/ServletApiResponse.java#L527
          ;; toString implementation calls getResponse,
          ;; https://github.com/jetty/jetty.project/blob/jetty-12.0.29/jetty-ee10/jetty-ee10-servlet/src/main/java/org/eclipse/jetty/ee10/servlet/ServletApiResponse.java#L97
          ;; getResponse throws IllegalStateException when `this._response` is null (probably after close)
          ;; https://github.com/jetty/jetty.project/blob/jetty-12.0.29/jetty-ee10/jetty-ee10-servlet/src/main/java/org/eclipse/jetty/ee10/servlet/ServletChannel.java#L300
          (when (realized? *ctx)
            (-> @*ctx
              ;; debug helpers: the `servlet-response` is in two places.
              #_(dissoc :servlet-response)
              #_(update :request dissoc :servlet-response)
              str)
            :ok))
      "It should be possible to represent the captured ctx as a string (it should not throw an exception)")))
