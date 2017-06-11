; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns gzip.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.jetty.util :as jetty-util]
            [ring.util.response :as ring-resp])
  (:import (org.eclipse.jetty.servlets DoSFilter)
           (org.eclipse.jetty.server.handler.gzip GzipHandler)))

(defn about-page
  [request]
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about-page))))

(defn home-page
  [request]
  (ring-resp/response "Hello World!"))

(def routes
  `[[["/" {:get home-page}
      ;; Set default interceptors for /about and any other paths under /
      ^:interceptors [(body-params/body-params) http/html-body]
      ["/about" {:get about-page}]]]])

;; Consumed by gzip.server/create-server
;; See http/default-interceptors for additional options you can configure
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; ::http/interceptors []
              ::http/routes routes

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;; ::http/allowed-origins ["scheme://host:port"]

              ;; Root for resource interceptor that is available by default.
              ::http/resource-path "/public"
              ::http/type :jetty

              ;; Add our filter-fn a the context configurator
              ::http/container-options {:context-configurator (fn [c]
                                                                (let [gzip-handler (GzipHandler.)]
                                                                  (.setGzipHandler c gzip-handler)
                                                                  ;; You can also add Servlet Filters...
                                                                  (jetty-util/add-servlet-filter c {:filter DoSFilter})
                                                                  c))}
              ; If you have a just a Servlet Filter...
              ;:container-options {:context-configurator #(jetty-util/add-servlet-filter % {:filter DoSFilter})}
              ;; ::http/host "localhost"
              ::http/port 8080})
