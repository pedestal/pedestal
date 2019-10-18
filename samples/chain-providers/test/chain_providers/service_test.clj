(ns chain-providers.service-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as http]
            [chain-providers.service :as service])
  (:import (java.net URL)))

(def ^:dynamic *service* nil)

(defn service-fixture
  [f]
  (binding [*service* (-> service/service (assoc ::http/port 0) http/create-server http/start)]
    (try
      (f)
      (finally
        (http/stop *service*)))))

(defn port
  "Returns bound port of the started service"
  [service]
  (some-> service ::http/server
          .getConnectors (aget 0) .getLocalPort))

(use-fixtures :once service-fixture)

(deftest home-page-test
  (is (= "Hello World"
         (slurp (URL. "http" "localhost" (port *service*) "/")))))
