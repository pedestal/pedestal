(ns immutant-web-sockets.service-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as http]
            [immutant-web-sockets.service :as service]))

(def service
  (::http/service-fn (http/create-servlet service/service)))

(deftest home-page-test
  (is (=
       (:body (response-for service :get "/"))
       "Hello World!"))
  (is (=
       {"Strict-Transport-Security" "max-age=31536000; includeSubdomains",
        "X-Frame-Options" "DENY",
        "X-Content-Type-Options" "nosniff",
        "X-XSS-Protection" "1; mode=block",
        "X-Download-Options" "noopen",
        "X-Permitted-Cross-Domain-Policies" "none",
        "Content-Security-Policy" "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;",
        "Content-Type" "text/html;charset=UTF-8"}
       (:headers (response-for service :get "/"))
       )))


(deftest about-page-test
  (is (.contains
       (:body (response-for service :get "/about"))
       "Clojure 1.8"))
  (is (=
       {"Strict-Transport-Security" "max-age=31536000; includeSubdomains",
        "X-Frame-Options" "DENY",
        "X-Content-Type-Options" "nosniff",
        "X-XSS-Protection" "1; mode=block",
        "X-Download-Options" "noopen",
        "X-Permitted-Cross-Domain-Policies" "none",
        "Content-Security-Policy" "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;",
        "Content-Type" "text/html;charset=UTF-8"}
       (:headers (response-for service :get "/about")))))
