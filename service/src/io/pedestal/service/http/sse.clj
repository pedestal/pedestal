; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.http.sse
  (:require [ring.util.response :as ring-response]
            [io.pedestal.service.http.servlet :refer :all]
            [io.pedestal.service.log :as log]
            [io.pedestal.service.interceptor :as interceptor :refer [definterceptorfn]]
            [clojure.core.async :as async]
            [clojure.stacktrace]
            [clojure.string :as string])
  (:import [java.nio.charset Charset]
           [java.io BufferedReader StringReader OutputStream]
           [java.util.concurrent Executors TimeUnit ScheduledExecutorService ScheduledFuture]
           [javax.servlet ServletResponse]))

(set! *warn-on-reflection* true)

(def ^String UTF-8 "UTF-8")

(defn get-bytes [^String s]
  (.getBytes s UTF-8))

(def CRLF (get-bytes "\r\n"))
(def EVENT_FIELD (get-bytes "event: "))
(def DATA_FIELD (get-bytes "data: "))
(def COMMENT_FIELD (get-bytes ": "))

(def ^ScheduledExecutorService scheduler (Executors/newScheduledThreadPool 1))

(defn mk-data [data]
  (apply str (map #(str "data:" % "\r\n")
                  (string/split data #"\r?\n"))))

(defn send-event [{channel ::channel} name data]
  (log/trace :msg "writing event to stream"
             :name name
             :data data)
  (try
    (locking channel
      (doseq [token [EVENT_FIELD (get-bytes name) CRLF (mk-data data) CRLF]]
        (async/>!! channel token)))
    (catch Throwable t
      (log/error :msg "exception sending event"
                 :throwable t
                 :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace t)))
      (throw t))))

(defn do-heartbeat [channel]
  (try
    (log/trace :msg "writing heartbeat to stream")
    (locking channel 
      (async/>!! channel CRLF))
    (catch Throwable t
      (log/error :msg "exception sending heartbeat"
                 :throwable t
                 :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace t)))
      (throw t))))

(defn- ^ScheduledFuture schedule-heartbeart [channel heartbeat-delay]
  (let [f #(do-heartbeat channel)]
    (.scheduleWithFixedDelay scheduler f 0 heartbeat-delay TimeUnit/SECONDS)))

(defn end-event-stream
  "Given a `context`, clean up the event stream it represents."
  [{end-fn ::end-event-stream}]
  (end-fn))

(defn start-stream
  "Given a `context`, starts an event stream using it's response and
  initiates a heartbeat to keep the connection alive. Also adds a
  reference to an end-stream function into context. An application
  must use this function to clean up a stream when it is no longer
  needed."
  [stream-ready-fn context heartbeat-delay]
  (let [channel (async/chan 1)
        response (-> (ring-response/response channel)
                     (ring-response/content-type "text/event-stream")
                     (ring-response/charset "UTF-8")
                     (ring-response/header "Connection" "close")
                     (ring-response/header "Cache-control" "no-cache")
                     (update-in [:headers] merge (:cors-headers context)))
        heartbeat (schedule-heartbeart channel heartbeat-delay)
        stream-context (merge context {:response response
                                       ::channel channel
                                       ::end-event-stream
                                       (fn []
                                         (log/trace :msg "ending sse stream")
                                         (.cancel heartbeat true)
                                         (async/close! channel))})]
    (log/debug :in :start-stream :new-context stream-context)
    (stream-ready-fn stream-context)
    stream-context))

(defn stream-events-fn
  "Stream events to the client by establishing an output stream as
  part of the context."
  [stream-ready-fn heartbeat-delay]
  (fn [{:keys [request] :as context}]
    (log/trace :msg "switching to sse")
    (start-stream stream-ready-fn context heartbeat-delay)))

(definterceptorfn start-event-stream
  "Returns an interceptor which will start a Server Sent Event stream
  with the requesting client, and set the ServletRepsonse to go
  async. After the request handling context has been paused in the
  Servlet thread, `stream-ready-fn` will be called in a future, with
  the resulting context from setting up the SSE event stream."
  ([stream-ready-fn] (start-event-stream stream-ready-fn 10))
  ([stream-ready-fn heartbeat-delay]
     (interceptor/interceptor
      :name "io.pedestal.service.http.sse/start-event-stream"
      :enter (stream-events-fn stream-ready-fn heartbeat-delay))))

(defn sse-setup
  "See start-event-stream. This function is for backward compatibility."
  [& args]
  (apply start-event-stream args))