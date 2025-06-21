(ns org.example.sse.main
  "Runs the org.example/sse service."
  (:require [io.pedestal.connector :as connector]
            [io.pedestal.log :as log]
            [io.pedestal.http.http-kit :as hk]
            [org.example.sse.connector :refer [connector-map]]))

(defn- log-startup
  [connector-map]
  (log/info :msg "Service org.example/sse startup"
            :port (:port connector-map))
  connector-map)

(defn start-service
  [_]
  (-> (connector-map {:join? true})
      log-startup
      (hk/create-connector nil)
      connector/start!))

