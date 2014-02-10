(ns dev
  (:require [io.pedestal.service.http :as bootstrap]
            [auto-reload-server.service :as service]
            [auto-reload-server.server :as server]
            [ns-tracker.core :as tracker]))

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

(defn- ns-reload [track]
 (try
   (doseq [ns-sym (track)]
     (require ns-sym :reload))
   (catch Throwable e (.printStackTrace e))))
 
(defn watch
  ([] (watch ["src"]))
  ([src-paths]
     (let [track (tracker/ns-tracker src-paths)
           done (atom false)]
       (doto
           (Thread. (fn []
                      (while (not @done)
                        (ns-reload track)
                        (Thread/sleep 500))))
         (.setDaemon true)
         (.start))
       (fn [] (swap! done not)))))

(defn -main [& args]
  (start)
  (watch))