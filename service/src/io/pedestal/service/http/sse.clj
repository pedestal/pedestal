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

(defn flush-response [^ServletResponse resp]
  (.flushBuffer resp))

(defn send-event [{response :servlet-response output-stream :output-stream :as context} name data]
  (log/trace :msg "writing event to stream"
             :servlet-response response
             :name name
             :data data)
  (try
    (locking response
      (let [data (mk-data data)]
        (servlet-interceptor/write-body-to-stream EVENT_FIELD output-stream)
        (servlet-interceptor/write-body-to-stream (get-bytes name) output-stream)
        (servlet-interceptor/write-body-to-stream CRLF output-stream)
        (servlet-interceptor/write-body-to-stream data output-stream)
        (servlet-interceptor/write-body-to-stream CRLF output-stream))
      (flush-response response))
    (catch Throwable t
      (log/error :msg "exception sending event"
                 :throwable t
                 :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace t)))
      (throw t))))

(defn do-heartbeat [{rsp :servlet-response
                     out :output-stream
                     :as context}]
  (try
    (locking rsp
      (log/trace :msg "writing heartbeat to stream")
      (servlet-interceptor/write-body-to-stream CRLF out)
      (flush-response rsp))
    (catch Throwable t
      (log/error :msg "exception sending heartbeat"
                 :throwable t
                 :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace t)))
      (throw t))))

(defn- ^ScheduledFuture schedule-heartbeart [context heartbeat-delay]
  (let [f #(do-heartbeat context)]
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
  [{{^ServletResponse servlet-response :servlet-response :as request} :request :as context} heartbeat-delay]
  (let [response (-> (ring-response/response "")
                     (ring-response/content-type "text/event-stream")
                     (ring-response/charset "UTF-8")
                     (ring-response/header "Connection" "close")
                     (ring-response/header "Cache-control" "no-cache"))
        new-context (merge context {:servlet-response servlet-response
                                    :output-stream (.getOutputStream servlet-response)
                                    :response response})]

    (log/trace :msg "starting sse handler")
    (servlet-interceptor/set-response servlet-response response)
    (flush-response servlet-response)
    (log/trace :msg "response headers sent")

    (let [hb-future (schedule-heartbeart new-context heartbeat-delay)]
      (assoc new-context ::end-event-stream
             (fn []
               (log/trace :msg "resuming after streaming"
                          :context new-context)
               (.cancel hb-future true)
               (interceptor-impl/resume new-context))))))

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

(definterceptorfn sse-setup
  "Returns an interceptor which will start a Server Sent Event stream
  with the requesting client, and set the ServletRepsonse to go
  async. After the request handling context has been paused in the
  Servlet thread, `stream-ready-fn` will be called in a future, with
  the resulting context from setting up the SSE event stream."
  ([stream-ready-fn] (sse-setup stream-ready-fn 10))
  ([stream-ready-fn heartbeat-delay]
     (interceptor/interceptor
      :name "io.pedestal.service.http.sse/sse-setup"
      :enter (stream-events-fn stream-ready-fn heartbeat-delay))))
