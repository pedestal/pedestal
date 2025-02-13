(ns io.pedestal.service.jetty-connector-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [io.pedestal.http.route.sawtooth :as sawtooth]
            [io.pedestal.service :as service]
            [io.pedestal.http.jetty :as jetty]
            [matcher-combinators.matchers :as m]
            [ring.util.response :refer [response]]
            [io.pedestal.service.test :as test]
            [io.pedestal.http.route.definition.table :as table]))

(defn hello-page
  [_request]
  (response "HELLO"))

(def routes
  (table/table-routes
    {}
    [["/hello" :get hello-page :route-name :hello]]))

(def *connector (atom nil))

(defn new-connector
  []
  (-> (service/default-service-map 8080)
      (service/with-default-interceptors)
      (service/with-routing sawtooth/router routes)
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
  (is (match? {:status 200
               :body (m/via slurp "HELLO")}
              (response-for :get "/hello"))))
