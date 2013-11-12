(ns {{namespace}}.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.service.http :as bootstrap]
            [{{namespace}}.service :as service]))

(defonce server-instance nil)

(defn create-server
  [service]
  (alter-var-root #'server-instance (constantly (bootstrap/create-server service))))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (create-server (merge service/service (apply hash-map args)))
  (bootstrap/start server-instance))

;; Fns for use with io.pedestal.servlet.ClojureVarServlet

(defn servlet-init [this config]
  (alter-var-root #'server-instance (constantly (bootstrap/servlet-init service/service config))))

(defn servlet-destroy [this]
  (alter-var-root #'server-instance bootstrap/servlet-destroy))

(defn servlet-service [this servlet-req servlet-resp]
  (bootstrap/servlet-service server-instance servlet-req servlet-resp))
