(ns io.pedestal.connector.with-default-interceptors-test
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.connector :as connector]
            [io.pedestal.connector.test :as test]
            [io.pedestal.http.http-kit :as hk]))

(deftest secure-headers
  (let [conn (-> 8080
               connector/default-connector-map
               (connector/with-routes #{["/" :get (constantly {:status 204})]})
               (connector/with-default-interceptors {:secure-headers {:hsts-settings "max-age=31536000; includeSubdomains"}})
               (hk/create-connector {}))]
    (is (= "max-age=31536000; includeSubdomains"
          (-> (test/response-for conn :get "/")
            :headers
            (get "Strict-Transport-Security")))))
  (let [conn (-> 8080
               connector/default-connector-map
               (connector/with-routes #{["/" :get (constantly {:status 204})]})
               (connector/with-default-interceptors {:secure-headers {:hsts-settings "max-age=3600"}})
               (hk/create-connector {}))]
    (is (= "max-age=3600"
          (-> (test/response-for conn :get "/")
            :headers
            (get "Strict-Transport-Security"))))))
