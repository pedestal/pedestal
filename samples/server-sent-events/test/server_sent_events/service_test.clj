(ns server-sent-events.service-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as bootstrap]
            [server-sent-events.service :as service]))

(def service
  (::bootstrap/service-fn (bootstrap/create-servlet service/service)))

(deftest home-page-test
  (is (=
       (:body (response-for service :get "/"))
       "Hello World!")))

(deftest about-page-test
  (is (.contains
       (:body (response-for service :get "/about"))
       "Clojure 1.5")))
