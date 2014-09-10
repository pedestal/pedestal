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

(ns io.pedestal.http.sse
  (:require [ring.util.response :as ring-response]
            [clojure.core.async :as async]
            [io.pedestal.http.servlet :refer :all]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor :refer [definterceptorfn]]
            [clojure.stacktrace]
            [clojure.string :as string])
  (:import [java.nio.charset Charset]
           [java.io BufferedReader StringReader OutputStream]
           [java.lang StringBuilder]
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

;; Cloned from core.async.impl.concurrent
(defn counted-thread-factory
  "Create a ThreadFactory that maintains a counter for naming Threads.
  name-format specifies thread names - use %d to include counter
  daemon is a flag for whether threads are daemons or not"
  [name-format daemon]
  (let [counter (atom 0)]
    (reify
      ThreadFactory
      (newThread [this runnable]
        (doto (Thread. runnable)
          (.setName (format name-format (swap! counter inc)))
          (.setDaemon daemon))))))

(def ^ThreadFactory daemon-thread-factory (counted-thread-factory "pedestal-sse-%d" true))
(def ^ScheduledExecutorService scheduler (Executors/newScheduledThreadPool 1 daemon-thread-factory))

(defn mk-data [name data]
  (let [sb (StringBuilder.)]
    (.append sb EVENT_FIELD)
    (.append sb (get-bytes name))
    (.append sb CRLF)

    (doseq [part (string/split data #"\r?\n")]
      (.append sb "data:")
      (.append sb part)
      (.append sb "\r\n"))

    (.append sb CRLF)
    (.toString sb)))


(defn send-event [channel name data]
  (log/trace :msg "writing event to stream"
             :name name
             :data data)
  (try
    (async/>!! channel (mk-data name data))
    (catch Throwable t
      (log/error :msg "exception sending event"
                 :throwable t
                 :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace t)))
      (throw t))))

(defn do-heartbeat [channel]
  (try
    (log/trace :msg "writing heartbeat to stream")
    (async/>!! channel CRLF)
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
  (let [response-channel (async/chan 1)
        response (-> (ring-response/response response-channel)
                     (ring-response/content-type "text/event-stream")
                     (ring-response/charset "UTF-8")
                     (ring-response/header "Connection" "close")
                     (ring-response/header "Cache-Control" "no-cache")
                     (update-in [:headers] merge (:cors-headers context)))
        heartbeat (schedule-heartbeart response-channel heartbeat-delay)
        event-channel (async/chan 1)]
    (async/thread
     (stream-ready-fn event-channel))

    (async/go
     (loop []
       (when-let [event (async/<! event-channel)]
         (send-event response-channel "event" (str event))
         (recur)))
     (.cancel ^ScheduledFuture heartbeat true)
     (async/close! response-channel))

    (assoc context :response response)))

(defn stream-events-fn
  "Stream events to the client by establishing an output stream as
  part of the context."
  [stream-ready-fn heartbeat-delay]
  (fn [{:keys [request] :as context}]
    (log/trace :msg "switching to sse")
    (start-stream stream-ready-fn context heartbeat-delay)))

(definterceptorfn start-event-stream
  "Returns an interceptor which will start a Server Sent Event stream
  with the requesting client, and set the ServletResponse to go
  async. After the request handling context has been paused in the
  Servlet thread, `stream-ready-fn` will be called in a future, with
  the resulting context from setting up the SSE event stream."
  ([stream-ready-fn] (start-event-stream stream-ready-fn 10))
  ([stream-ready-fn heartbeat-delay]
     (interceptor/interceptor
      :name "io.pedestal.http.sse/start-event-stream"
      :enter (stream-events-fn stream-ready-fn heartbeat-delay))))

(defn sse-setup
  "See start-event-stream. This function is for backward compatibility."
  [& args]
  (apply start-event-stream args))
