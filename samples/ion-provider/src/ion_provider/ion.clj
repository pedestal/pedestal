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

(comment

 (def h (handler service/service))

 (slurp (:body (h {:server-port    0
                   :server-name    "localhost"
                   :remote-addr    "127.0.0.1"
                   :uri            "/pets"
                   :scheme         "http"
                   :request-method :get
                   :headers        {}})))


 )
