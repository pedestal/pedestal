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

(ns cors.service
  (:require [clojure.java.io :as io]
            [io.pedestal.interceptor :refer [defhandler defbefore defafter definterceptor]]
            [io.pedestal.log :as log]
            [io.pedestal.http :as bootstrap]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [io.pedestal.http.sse :refer [sse-setup send-event end-event-stream]]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [ring.util.response :as ring-response]
            [ring.middleware.cors :as cors]))

(defn send-thread-id [context]
  (send-event context "thread-id" (str (.getId (Thread/currentThread)))))

(defn thread-id-sender [{{^ServletResponse response :servlet-response
                 :as request} :request :as context}]

  (log/info :msg "starting sending thread id")
  (dotimes [_ 10]
    (Thread/sleep 3000)
    (send-thread-id context))
  (log/info :msg "stopping sending thread id")

  (end-event-stream context))

(defhandler send-js
  "Send the client a response containing the stub JS which consumes an
  event source."
  [req]
  (log/info :msg "returning js")
  (-> (ring-response/response (slurp (io/resource "blob.html")))
      (ring-response/content-type "text/html")))

(definterceptor thread-id-sender (sse-setup thread-id-sender))

(defroutes routes
  [[["/js" {:get send-js}]
    ["/" {:get thread-id-sender}]]])


;; You can use this fn or a per-request fn via io.pedestal.http.route/url-for
(def url-for (route/url-for-routes routes))

;; Consumed by cors.server/create-server
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; :bootstrap/interceptors []
              ::bootstrap/routes routes
              ::bootstrap/allowed-origins ["http://localhost:8080"]
              ;; Root for resource interceptor that is available by default.
              ::bootstrap/resource-path "/public"
              ;; Choose from [:jetty :tomcat].
              ::bootstrap/type :jetty
              ::bootstrap/port 8081})
