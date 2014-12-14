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

(ns server-sent-events.service
  (:require [io.pedestal.http :as bootstrap]
            [io.pedestal.http.sse :as sse]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [ring.util.response :as ring-resp]
            [clojure.core.async :as async]))

(defn send-counter
  "Counts down to 0, sending value of counter to sse context and
  recursing on a different thread; ends event stream when counter
  is 0."
  [event-ch count-num]
  ;; This is how you set a specific event name for the client to listen for
  (async/put! event-ch {:name "count"
                        :data (str count-num ", thread: " (.getId (Thread/currentThread)))})
  ;; If you just want the client to receive messages on the "message" event, just pass the data string
  ;(async/put! event-ch (str count-num ", thread: " (.getId (Thread/currentThread))))
  (Thread/sleep 1500)
  (if (> count-num 0)
    (recur event-ch (dec count-num))
    (do
      (async/put! event-ch {:name "close" :data ""})
      (async/close! event-ch))))

(defn sse-stream-ready
  "Starts sending counter events to client."
  [event-ch ctx]
  ;; The context is passed into this function - it contains everything you'd
  ;; expect.  Additionally, there's a `response-channel` in the context.  This
  ;; is the channel the connects directly to the response OutputStream, should
  ;; you ever need low-level control over the SSE events.  It's advised that
  ;; that you never use this channel unless you know what you're doing.
  (let [{:keys [request response-channel]} ctx]
    (send-counter event-ch 10)))

(defn about-page
  [request]
  (ring-resp/response "Server Sent Service"))

;; Wire root URL to sse event stream
(defroutes routes
  [[["/" {:get [::send-counter (sse/start-event-stream sse-stream-ready)]}
     ["/about" {:get about-page}]]]])

;; You can use this fn or a per-request fn via io.pedestal.service.http.route/url-for
(def url-for (route/url-for-routes routes))

;; Consumed by server-sent-events.server/create-server
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; :bootstrap/interceptors []
              ::bootstrap/routes routes
              ;; Root for resource interceptor that is available by default.
              ::bootstrap/resource-path "/public"
              ;; Either :jetty or :tomcat (see comments in project.clj
              ;; to enable Tomcat)
              ::bootstrap/type :jetty
              ::bootstrap/port 8080})
