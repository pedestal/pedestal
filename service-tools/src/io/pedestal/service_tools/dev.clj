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
            [ns-tracker.core :as tracker]))

(defn init
  "Initialize a development service for use by (start).

  Arguments are:
  * service - an application service map."
  [service]
  (-> service ;; start with production configuration
      (merge {:env :dev
              ;; do not block thread that starts web server
              ::bootstrap/join? false
              ;; all origins are allowed in dev mode
              ::bootstrap/allowed-origins {:creds true :allowed-origins (constantly true)}})
      (bootstrap/default-interceptors)
      (bootstrap/dev-interceptors)))

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

