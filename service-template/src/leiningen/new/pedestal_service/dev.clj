(ns dev
  (:require [{{namespace}}.service :as service]
            [{{namespace}}.server :as server]
            [io.pedestal.service.http :as bootstrap]
            [io.pedestal.service.http.route :as route]
            [io.pedestal.service-tools.dev :as dev-tools :refer [watch tools-help]]))

(def service (dev-tools/init (let [interceptors (::bootstrap/interceptors service/service)
                                   routes       #(deref #'service/routes)]
                               (cond-> service/service
                                       ;; reload routes on every request
                                       true (merge {::bootstrap/routes routes})
                                       ;; update the router when the interceptors are defined
                                       (not (nil? interceptors)) (update-in [::bootstrap/interceptors]
                                                                            (fn [interceptors]
                                                                              (mapv #(if (= (:name %) ::route/router)
                                                                                       (route/router routes)
                                                                                       %) interceptors)))))))


(defn start
  "Start a development web server. Default port is 8080.

  You must call init prior to calling start."
  [& [opts]]
  (server/create-server (merge service opts))
  (bootstrap/start server/server-instance)
  :ok)

(defn stop
  "Stop the current web server."
  []
  (bootstrap/stop server/server-instance)
  :ok)

(defn restart
  "Stop, then start the current web server."
  []
  (stop)
  (start))

(defn -main
  "The entry-point for 'lein run-dev'. Starts a web server and watches the projects files for any changes."
  [& args]
  (start)
  (watch))
