;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.service.http.tomcat
  (:require [clojure.java.io :as io])
  (:import (org.apache.catalina.startup Tomcat)
           (javax.servlet Servlet)))

(defn- create-server
  "Constructs a Tomcat Server instance."
  [^Servlet servlet
   {:keys [port]
    :or {port 8080}
    :as options}]
  (let [basedir (str "tmp/tomcat." port)
        public (io/file basedir "public")]
    (.mkdirs (io/file basedir "webapps"))
    (.mkdirs public)
    (let [tomcat (doto (Tomcat.)
                   (.setPort port)
                   (.setBaseDir basedir))
          context (.addContext tomcat "/" (.getAbsolutePath public))]
      (Tomcat/addServlet context "default" servlet)
      (.addServletMapping context "/*" "default")
      tomcat)))

(defn start
  [^Tomcat server
   {:keys [join?]
    :or {join? true}}]
  (.start server)
  (when join? (.await (.getServer server))))

(defn stop [^Tomcat server]
  (.stop server))

(defn server
  ([servlet]
     (server servlet {}))
  ([servlet options]
     (let [server (create-server servlet options)]
       {:server   server
        :start-fn #(start server options)
        :stop-fn  #(stop server)})))
