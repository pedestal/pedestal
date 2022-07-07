(ns json-api.service-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer [response-for]]
            [cheshire.core :as json]
            [io.pedestal.http :as bootstrap]
            [json-api.service :as service]))

(def service
  (::bootstrap/service-fn (bootstrap/create-servlet service/service)))

(deftest home-page-test
  (is (=
       (json/parse-string (:body (response-for service :get "/")))
       {"hello" "world"}))
  (is (=
       (:headers (response-for service :get "/"))
       {"Content-Type" "application/json;charset=UTF-8"
        "Strict-Transport-Security" "max-age=31536000; includeSubdomains"
        "X-Frame-Options" "DENY"
        "X-Content-Type-Options" "nosniff"
        "X-XSS-Protection" "1; mode=block"
        "X-Download-Options" "noopen"
        "X-Permitted-Cross-Domain-Policies" "none"
        "Content-Security-Policy" "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"})))

(deftest about-page-test
  (is (.contains
       (:body (response-for service :get "/about"))
       "Clojure 1.9"))
  (is (=
       (:headers (response-for service :get "/about"))
       {"Content-Type" "application/json;charset=UTF-8"
        "Content-Length" 58
        "Strict-Transport-Security" "max-age=31536000; includeSubdomains"
        "X-Frame-Options" "DENY"
        "X-Content-Type-Options" "nosniff"
        "X-XSS-Protection" "1; mode=block"
        "X-Download-Options" "noopen"
        "X-Permitted-Cross-Domain-Policies" "none"
        "Content-Security-Policy" "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"})))

