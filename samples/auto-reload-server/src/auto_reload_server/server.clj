(ns auto-reload-server.server
  (:gen-class) ; for -main method in uberjar
  (:require [auto-reload-server.service :as service]
            [io.pedestal.service.http :as bootstrap]))

(defonce service-instance nil)

(defn create-server
  "Standalone dev/prod mode."
  [& [opts]]
  (alter-var-root #'service-instance
                  (constantly (bootstrap/create-server (merge service/service opts)))))

(defn -main [& args]
  (create-server)
  (bootstrap/start service-instance))


;; Container prod mode for use with the io.pedestal.servlet.ClojureVarServlet class.

(defn servlet-init [this config]
  (alter-var-root #'service-instance
                  (constantly (bootstrap/create-servlet service/service)))
  (.init (::bootstrap/servlet service-instance) config))

(defn servlet-destroy [this]
  (alter-var-root #'service-instance nil))

(defn servlet-service [this servlet-req servlet-resp]
  (.service ^javax.servlet.Servlet (::bootstrap/servlet service-instance)
            servlet-req servlet-resp))
