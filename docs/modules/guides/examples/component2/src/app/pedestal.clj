(ns app.pedestal
  (:require [com.stuartsierra.component :as component]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.http-kit :as hk]
            [app.routes :as routes]))

(defrecord Pedestal [route-source connector]                ;; <1>
  component/Lifecycle

  (start [this]
    (assoc this :connector
           (-> (conn/default-connector-map 8890)
               (conn/optionally-with-dev-mode-interceptors)
               (conn/with-default-interceptors)
               (conn/with-routes (routes/routes route-source)) ;; <2>
               (hk/create-connector nil)
               (conn/start!))))

  (stop [this]
    (conn/stop! connector)
    (assoc this :connector nil)))

(defn new-pedestal
  []
  (map->Pedestal {}))
