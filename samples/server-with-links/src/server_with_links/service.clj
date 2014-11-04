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

(ns server-with-links.service
    (:require [io.pedestal.http :as bootstrap]
              [io.pedestal.http.route :as route]
              [io.pedestal.http.body-params :as body-params]
              [io.pedestal.interceptor :refer [defhandler]]
              [io.pedestal.http.route.definition :refer [defroutes]]
              [ring.util.response :as ring-resp]))

(defn link-to
  "Uses pedestal.service.http.route/url-for fn to generate a link to a named route"
  [text route]
  (format "<a href='%s'>%s</a>"
          (route/url-for route)
          text))

(defhandler this-page
  [request]
  (-> (ring-resp/response (format "<body>
This isn't what you're looking for. Go to %s.
</body>"
                                  (link-to "that" :that)))
      (ring-resp/content-type "text/html")))

(defhandler that-page
  [request]
  (-> (ring-resp/response "That page")
      (ring-resp/content-type "text/html")))

(defroutes routes
  [[; Unnamed route
    ["/" {:get this-page}]
    ; Name a route to be able to generate its path later
    ["/that" {:get [:that that-page]}]]])

;; Consumed by server-with-links.server/create-server
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; :bootstrap/interceptors []
              ::bootstrap/routes routes
              ;; Root for resource interceptor that is available by default.
              ::bootstrap/resource-path "/public"
              ;; Choose from [:jetty :tomcat].
              ::bootstrap/type :jetty
              ::bootstrap/port 8080})
