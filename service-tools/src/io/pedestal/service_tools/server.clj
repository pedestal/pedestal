; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service-tools.server
  (:require [io.pedestal.service.http :as bootstrap]))

(def service nil)

(defonce service-instance nil)

(defn init
  "Initialize a service for use with -main."
  [user-service]
  (alter-var-root #'service (constantly user-service)))

(defn create-server
  "Standalone dev/prod mode."
  [& [opts]]
  (alter-var-root #'service-instance
                  (constantly (bootstrap/create-server (merge service opts)))))

(defn -main [& args]
  (create-server)
  (bootstrap/start service-instance))


;; Container prod mode for use with the io.pedestal.servlet.ClojureVarServlet class.

(defn servlet-init [this config]
  (alter-var-root #'service-instance
                  (constantly (bootstrap/create-servlet service)))
  (.init (::bootstrap/servlet service-instance) config))

(defn servlet-destroy [this]
  (alter-var-root #'service-instance (constantly nil)))

(defn servlet-service [this servlet-req servlet-resp]
  (.service ^javax.servlet.Servlet (::bootstrap/servlet service-instance)
            servlet-req servlet-resp))
