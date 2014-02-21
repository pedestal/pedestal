; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ring-middleware.server
  (:require [ring-middleware.service :as service]
            [io.pedestal.service.http :as bootstrap]))

(def service-instance
  "Global var to hold service instance."
  nil)

(defn create-server
  "Standalone dev/prod mode."
  [& [opts]]
  (alter-var-root #'service-instance
                  (constantly (bootstrap/create-server (merge service/service opts)))))

(defn -main [& args]
  (println "Creating server...")
  (create-server)
  (println "Server created. Awaiting connections.")
  (bootstrap/start service-instance))


;; Container prod mode for use with the pedestal.servlet.ClojureVarServlet class.

(defn servlet-init [this config]
  (require 'ring-middleware.service)
  (alter-var-root #'service-instance (bootstrap/create-servlet service/service))
  (bootstrap/start service-instance)
  (.init (::bootstrap/servlet service-instance) config))

(defn servlet-destroy [this]
  (bootstrap/stop service-instance)
  (alter-var-root #'service-instance nil))

(defn servlet-service [this servlet-req servlet-resp]
  (.service ^javax.servlet.Servlet (::bootstrap/servlet service-instance)
            servlet-req servlet-resp))
