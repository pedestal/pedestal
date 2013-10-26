; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service-tools.dev
  (:require [io.pedestal.service.http :as bootstrap]
            [io.pedestal.service.http.route :as route]
            [io.pedestal.service-tools.server :as server]
            [ns-tracker.core :as tracker]))

(defn init
  "Initialize a development service for use by (start).

  Arguments are:
  * user-service - an application level service map.
  * routes-var - a var referencing an applications routes map. This is a var
                 specifically so routes can be reloaded per-request."
  [user-service routes-var]
  (let [interceptors (::bootstrap/interceptors user-service)
        routes #(deref routes-var)]
    (server/init (cond-> user-service ;; start with production configuration
                   true (merge {:env :dev
                                ;; do not block thread that starts web server
                                ::bootstrap/join? false
                                ;; reload routes on every request
                                ::bootstrap/routes routes
                                ;; all origins are allowed in dev mode
                                ::bootstrap/allowed-origins {:creds true :allowed-origins (constantly true)}})
                   true bootstrap/default-interceptors
                   ;; update the router to support routes reloading when the interceptors are defined
                   (not (nil? interceptors)) (update-in [::bootstrap/interceptors]
                                                        (fn [interceptors]
                                                          (vec (map #(if (= (:name %) ::route/router)
                                                                      (route/router routes)
                                                                      %) interceptors))))
                   true bootstrap/dev-interceptors))))

(defn start
  "Start a development web server. Default port is 8080.

  You must call init prior to calling start."
  [& [opts]]
  (server/create-server (merge server/service opts))
  (bootstrap/start server/service-instance))

(defn stop
  "Stop the current web server."
  []
  (bootstrap/stop server/service-instance))

(defn restart
  "Stop, then start the current web server."
  []
  (stop)
  (start))

(defn- ns-reload [track]
 (try
   (doseq [ns-sym (track)]
     (require ns-sym :reload))
   (catch Throwable e (.printStackTrace e))))

(defn watch
  "Watches a list of directories for file changes, reloading them as necessary."
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

(defn tools-help
    "Show basic help for each function in this namespace."
    []
    (println)
    (println "Start a new service development server with (start) or (start service-options)")
    (println "----")
    (println "Type (start) or (start service-options) to initialize and start a server")
    (println "Type (stop) to stop the current server")
    (println "Type (restart) to restart the current server")
    (println "----")
    (println "Type (watch) to watch for changes in the src/ directory")
    (println))

(defn -main
  "The entry-point for 'lein run-dev'. Starts a web server and watches the projects files for any changes."
  [& args]
  (start)
  (watch))
