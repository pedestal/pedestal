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
            [io.pedestal.service.impl.interceptor :as interceptor-impl]
            [io.pedestal.service.http.impl.servlet-interceptor :as servlet-interceptor]
            [clojure.stacktrace]
            [clojure.string :as string])
  (:import [java.nio.charset Charset]
           [java.io BufferedReader StringReader OutputStream]
           [java.util.concurrent Executors ThreadFactory TimeUnit ScheduledExecutorService ScheduledFuture]
           [javax.servlet ServletResponse]))

(set! *warn-on-reflection* true)

(def ^String UTF-8 "UTF-8")

(defn get-bytes [^String s]
  (.getBytes s UTF-8))

(def CRLF (get-bytes "\r\n"))
(def EVENT_FIELD (get-bytes "event: "))
(def DATA_FIELD (get-bytes "data: "))
(def COMMENT_FIELD (get-bytes ": "))

(def ^ThreadFactory daemon-thread-factory (reify
                                            ThreadFactory
                                            (newThread [this runnable]
                                              (doto (Thread. runnable)
                                                (.setDaemon true)))))

(def ^ScheduledExecutorService scheduler (Executors/newScheduledThreadPool 1 daemon-thread-factory))

(defn mk-data [data]
  (apply str (map #(str "data:" % "\r\n")
                  (string/split data #"\r?\n"))))

(defn send-event [stream-context name data]
  (log/trace :msg "writing event to stream"
             :name name
             :data data)
  (try
    (locking (servlet-interceptor/lockable stream-context)
      (let [data (mk-data data)]
        (servlet-interceptor/write-response-body stream-context EVENT_FIELD)
        (servlet-interceptor/write-response-body stream-context (get-bytes name))
        (servlet-interceptor/write-response-body stream-context CRLF)
        (servlet-interceptor/write-response-body stream-context data)
        (servlet-interceptor/write-response-body stream-context CRLF))
      (servlet-interceptor/flush-response stream-context))
    (catch Throwable t
      (log/error :msg "exception sending event"
                 :throwable t
                 :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace t)))
      (throw t))))

(defn do-heartbeat [stream-context]
  (try
    (locking (servlet-interceptor/lockable stream-context)
      (log/trace :msg "writing heartbeat to stream")
      (servlet-interceptor/write-response-body stream-context CRLF)
      (servlet-interceptor/flush-response stream-context))
    (catch Throwable t
      (log/error :msg "exception sending heartbeat"
                 :throwable t
                 :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace t)))
      (throw t))))

(defn- ^ScheduledFuture schedule-heartbeart [stream-context heartbeat-delay]
  (let [f #(do-heartbeat stream-context)]
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
  [stream-context heartbeat-delay]
  (let [response (-> (ring-response/response "")
                     (ring-response/content-type "text/event-stream")
                     (ring-response/charset "UTF-8")
                     (ring-response/header "Connection" "close")
                     (ring-response/header "Cache-control" "no-cache")
                     (update-in [:headers] merge (:cors-headers stream-context)))]
    (log/debug :in :start-stream :response response)
    (log/trace :msg "starting sse handler")
    (servlet-interceptor/write-response stream-context response)
    (servlet-interceptor/flush-response stream-context)
    (log/trace :msg "response headers sent")

    (let [hb-future (schedule-heartbeart stream-context heartbeat-delay)]
      (assoc stream-context ::end-event-stream
             (fn []
               (log/trace :msg "resuming after streaming"
                          :context stream-context)
               (.cancel hb-future true)
               (interceptor-impl/resume stream-context))))))

(defn stream-events-fn
  "Stream events to the client by establishing an output stream as
  part of the context."
  [stream-ready-fn heartbeat-delay]
  (fn [{:keys [request] :as context}]
    (interceptor-impl/with-pause [post-pause-context context]
      (log/trace :msg "switching to sse")
      (future
        (try
          (stream-ready-fn (start-stream post-pause-context heartbeat-delay))
          (catch Throwable t
            (log/error :msg "exception starting stream"
                       :throwable t
                       :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace t)))
            (throw t)))))))

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
