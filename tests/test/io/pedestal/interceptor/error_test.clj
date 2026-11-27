; Copyright 2024-2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor.error-test
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.connector :as conn]
            [io.pedestal.servlet-support :as servlet-support]
            [ring.util.response :as ring-resp]
            [io.pedestal.interceptor.error :as error-int]))

(def service-error-handler
  (error-int/error-dispatch [ctx ex]
                            [{:exception-type :java.lang.ArithmeticException
                              :interceptor    ::another-bad-one}]
                            (assoc ctx :response {:status 400 :body "Another bad one"})

                            [{:exception-type :java.lang.ArithmeticException}]
                            (assoc ctx :response {:status 400 :body "A bad one"})

                            ;; If we don't match, forward it on
                            :else
                            (chain/with-error ctx ex)))


(defn bad-page
  [_request]
  (ring-resp/response (str "Bad division: " (/ 3 0))))

(defn drop-through
  [_request]
  (throw (Exception. "Just testing the error-handler, this is not a real exception")))

(def request-handling-routes
  [[:request-handling "error-dispatch.pedestal"
    ["/" ^:interceptors [service-error-handler]
     ["/div" {:any #'bad-page}]
     ["/div2" {:any [::another-bad-one #'bad-page]}]
     ["/drop" {:any #'drop-through}]]]])

;; The default interceptors, with the routes.
;; The 500 response for an uncaught exception comes from the servlet interceptors, so the
;; connector map runs in a ConnectorServlet (see io.pedestal.servlet-support).
(def app (binding [interceptor/*default-handler-names* false]
           (servlet-support/create-servlet
             (-> (conn/default-connector-map -1)
                 (conn/with-default-interceptors)
                 (conn/with-routes request-handling-routes)))))

(defn ^:private response-for
  [servlet verb url]
  (servlet-support/response-for servlet verb url))

(def url "http://error-dispatch.pedestal/div")
(def url-two (str url "2"))
(def drop-url "http://error-dispatch.pedestal/drop")

(deftest captures-generic-exception
  (is (= (:body (response-for app :get url))
         "A bad one")))

(deftest captures-specific-exception
  (is (= (:body (response-for app :get url-two))
         "Another bad one")))

(deftest allows-fallthrough-behavior
  (println "This test will log an ERROR, which can be ignored.")
  (let [boom-resp (response-for app :get drop-url)]
    (is (match? {:status 500
                 :body   "Internal server error: exception"}
                boom-resp))))

