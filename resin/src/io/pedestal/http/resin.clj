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

(ns io.pedestal.http.resin
  (:require [clojure.java.io :as io])
  (:import (com.caucho.resin HttpEmbed ResinEmbed ServletEmbed
                             ServletMappingEmbed WebAppEmbed)
           (javax.servlet Servlet)))

(defn- create-server
  "Constructs a Resin Server instance."
  [^Servlet servlet
   {:keys [port]
    :or {port 8080}
    :as options}]
  (let [basedir (str "tmp/resin." port)
        public (io/file basedir "public")]
    (.mkdirs (io/file basedir "webapps"))
    (.mkdirs public)
    (let [class-name (.getCanonicalName (.getClass servlet))
          webapp (doto (WebAppEmbed. "/" (.getAbsolutePath public))
                   (.addServlet (ServletEmbed. class-name "default"))
                   (.addServletMapping (ServletMappingEmbed. "default" "/*")))
          resin (doto (ResinEmbed.)
                  (.setRootDirectory basedir)
                  (.addPort (HttpEmbed. port))
                  (.addWebApp webapp))]
      resin)))

(defn start
  [^ResinEmbed server
   {:keys [join?]
    :or {join? true}}]
  (.start server)
  (when join? (.join server))
  (.join server)
  server)

(defn stop [^ResinEmbed server]
  (.stop server)
  server)

(defn server
  ([servlet]
     (server servlet {}))
  ([servlet options]
     (let [server (create-server servlet options)]
       {:server   server
        :start-fn #(start server options)
        :stop-fn  #(stop server)})))
