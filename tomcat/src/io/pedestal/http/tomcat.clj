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

(ns io.pedestal.http.tomcat
  (:require [clojure.java.io :as io])
  (:import (org.apache.catalina.startup Tomcat)
           (org.apache.catalina.connector Connector)
           (javax.servlet Servlet)))

(def ssl-default-config
  {:ssl-port 8443
   :client-auth :none})

;; TODO: How many set operations on Connector should we support?
(defn- ssl-conn-factory
  [opts]
  (let [opts      (merge ssl-default-config opts)
        connector (doto (Connector.)
                    (.setPort (:ssl-port opts))
                    (.setSecure true)
                    (.setScheme "https")
                    (.setAttribute "SSLEnabled" true)
                    (.setAttribute "sslProtocol" "TLS")
                    (.setAttribute "clientAuth" (not= :none (:client-auth opts))))
        _         (if (and (:keysore opts) (:key-password opts))
                    (.setAttribute connector "keystoreFile" (:keystore opts))
                    (.setAttribute connector "keystorePass" (:key-password opts)))]
    connector))

(defn- create-server
  "Constructs a Tomcat Server instance."
  [^Servlet servlet
   {:keys [port]
    :or {port 8080}
    :as options}]
  (let [basedir                 (str "tmp/tomcat." port)
        public                  (io/file basedir "public")
        {:keys [ssl? ssl-port]} (:container-options options)
        ssl-connector           (when (or ssl? ssl-port)
                                  (ssl-conn-factory (:container-options options)))]
    (.mkdirs (io/file basedir "webapps"))
    (.mkdirs public)
    (let [tomcat (doto (Tomcat.)
                   (.setPort port)
                   (.setBaseDir basedir))
          context (.addContext tomcat "/" (.getAbsolutePath public))]
      (Tomcat/addServlet context "default" servlet)
      (.addServletMapping context "/*" "default")
      (if ssl-connector
        (-> tomcat .getService (.addConnector ssl-connector)))
      tomcat)))

(defn start
  [^Tomcat server
   {:keys [join?]
    :or {join? true}}]
  (.start server)
  (when join? (.await (.getServer server))))

(defn stop [^Tomcat server]
  (.stop server))

(defn destroy [^Tomcat server]
  (.destroy server))

(defn server
  ([service-map]
     (server service-map {}))
  ([service-map options]
     (let [server (create-server (:io.pedestal.http/servlet service-map) options)]
       {:server   server
        :start-fn #(start server options)
        :stop-fn  #(stop server)
        :destroy-fn #(destroy server)})))

;; :ssl?         - allow connections over HTTPS
;; :ssl-port     - the SSL port to listen on (defaults to 8443, implies :ssl?)
;; :keystore     - the keystore to use for SSL connections
;; :key-password - the password to the keystore
;; :client-auth  - SSL client certificate authenticate, may be set to :need,
;;                 :want or :none (defaults to :none)"
