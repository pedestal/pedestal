; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns server-with-links.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]))

(defn link-to
  "Uses io.pedestal.http.route/url-for fn to generate a link to a named route"
  [text route]
  (format "<a href='%s'>%s</a>"
          (route/url-for route)
          text))

(defn home-page
  [request]
  (-> (ring-resp/response (format "<body>This isn't what you're looking for. Go to %s.</body>\n"
                                  (link-to "that" :that)))
      (ring-resp/content-type "text/html")))

(defn linked-page
  [request]
  (-> (ring-resp/response "<body>This <em>is</em> the page you are looking for!</body>\n")
      (ring-resp/content-type "text/html")))

(def routes
  `[[["/" {:get home-page}]                   ; Unless otherwise named, the name of a route defaults
                                              ; to the namespaced keyword form of the symbol. In this case,
                                              ; the route is named :server-with-links.service/home-page
     ["/that" {:get [:that linked-page]}]]])  ; Name a route to be able to generate its path later

;; Consumed by server-with-links.server/create-server
;; See http/default-interceptors for additional options you can configure
(def service {:env :prod
              ::http/routes routes
              ::http/resource-path "/public"
              ::http/type :jetty
              ::http/port 8080})
