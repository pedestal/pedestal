(ns hello-world.server
  (:require [io.pedestal.http :as server]
            [hello-world.service :as service]))

(defonce runnable-service (server/create-server service/service))

(defn -main
  [& args]
  (println "\nCreating your server...")
  (server/start runnable-service))

