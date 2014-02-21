(ns dev
  (:require [io.pedestal.service.http :as bootstrap]
            [jboss.service :as service]
            [jboss.server :as server]))

(def service (-> service/service ;; start with production configuration
                 (merge  {:env :dev
                          ;; do not block thread that starts web server
                          ::bootstrap/join? false
                          ;; reload routes on every request
                          ::bootstrap/routes #(deref #'service/routes)
                          ;; all origins are allowed in dev mode
                          ::bootstrap/allowed-origins (constantly true)})
                 (bootstrap/default-interceptors)
                 (bootstrap/dev-interceptors)))

(defn start
  [& [opts]]
  (server/create-server (merge service opts))
  (bootstrap/start server/service-instance))

(defn stop
  []
  (bootstrap/stop server/service-instance))

(defn restart
  []
  (stop)
  (start))

