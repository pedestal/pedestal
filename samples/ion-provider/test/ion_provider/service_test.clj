(ns ion-provider.service-test
  (:require [clojure.test :refer :all]
            [ion-provider.service :as service]
            [ion-provider.ion :as ion]))

(def service (ion/handler service/service))

(defn- response-for
  [service & {:keys [server-port
                     server-name
                     remote-addr
                     uri
                     scheme
                     method
                     headers]
              :or   {server-port 0
                     server-name "localhost"
                     remote-addr "127.0.0.1"
                     scheme      :http
                     headers     {}}}]
  (service {:server-port    server-port
            :server-name    server-name
            :remote-addr    remote-addr
            :uri            uri
            :scheme         scheme
            :request-method method
            :headers        headers}))

(deftest home-page-test
  (is (= (:body (response-for service :uri "/" :method :get))
         "Hello World!"))
  (is (=
       (:headers (response-for service :uri "/" :method :get))
       {"Content-Type" "text/html;charset=UTF-8"
        "Strict-Transport-Security" "max-age=31536000; includeSubdomains"
        "X-Frame-Options" "DENY"
        "X-Content-Type-Options" "nosniff"
        "X-XSS-Protection" "1; mode=block"
        "X-Download-Options" "noopen"
        "X-Permitted-Cross-Domain-Policies" "none"
        "Content-Security-Policy" "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"})))

(deftest about-page-test
  (is (.contains
       (:body (response-for service :uri "/about" :method :get))
       "Clojure 1.9"))
  (is (=
       (:headers (response-for service :uri "/about" :method :get))
       {"Content-Type" "text/html;charset=UTF-8"
        "Strict-Transport-Security" "max-age=31536000; includeSubdomains"
        "X-Frame-Options" "DENY"
        "X-Content-Type-Options" "nosniff"
        "X-XSS-Protection" "1; mode=block"
        "X-Download-Options" "noopen"
        "X-Permitted-Cross-Domain-Policies" "none"
        "Content-Security-Policy" "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"})))
