(ns ion-provider.ion
  (:require [datomic.ion.lambda.api-gateway :as apig]
            [io.pedestal.http :as http]
            [ion-provider.service :as service]))

(defn handler
  "Ion handler"
  [service-map]
  (-> service-map
      http/default-interceptors
      http/create-provider))

(def app (apig/ionize (handler service/service)))
