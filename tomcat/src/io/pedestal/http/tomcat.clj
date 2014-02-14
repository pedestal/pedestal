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
