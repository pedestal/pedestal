; Copyright 2024-2025 Nubank NA
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

(ns io.pedestal.http.sse
  "Support for Server Sent Events."
  (:require [io.pedestal.metrics :as metrics]
            [ring.util.response :as ring-response]
            [clojure.core.async :refer [go chan >! <!! timeout alts! put! close! thread]]
            [io.pedestal.log :as log]
            [io.pedestal.internal :as i]
            [io.pedestal.interceptor :as interceptor]
            [clojure.string :as string])
  (:import (com.fasterxml.jackson.core.util ByteArrayBuilder)))

(def ^:private ^String UTF-8 "UTF-8")

(defn- get-bytes [^String s]
  (.getBytes s UTF-8))

(def ^:private EOL (get-bytes "\n"))
(def ^:private EVENT_FIELD (get-bytes "event: "))
(def ^:private DATA_FIELD (get-bytes "data: "))
(def ^:private ID_FIELD (get-bytes "id: "))

(defn- event->bytes
  [name data id]
  (let [bab (ByteArrayBuilder.)]
    (when name
      (.write bab ^bytes EVENT_FIELD)
      (.write bab ^bytes (get-bytes name))
      (.write bab ^bytes EOL))

    (doseq [part (string/split-lines data)]
      (.write bab ^bytes DATA_FIELD)
      (.write bab ^bytes (get-bytes part))
      (.write bab ^bytes EOL))

    (when id
      (.write bab ^bytes ID_FIELD)
      (.write bab ^bytes (get-bytes id))
      (.write bab ^bytes EOL))

    (.write bab ^bytes EOL)
    (.toByteArray bab)))

(def ^:private payload-size-fn (metrics/histogram ::payload-size nil))

(defn- send-event
  [response-channel name data id]
  (log/trace :msg "writing event to stream"
             :name name
             :data data
             :id id)
  (try
    (let [event-bytes (event->bytes name data id)]
      ;; In 0.7 and earlier, this was the size of data, which isn't the full payload size.
      (payload-size-fn (count event-bytes))
      (put! response-channel event-bytes))
    (catch Throwable t
      (close! response-channel)
      (log/error :msg "exception sending event"
                 :throwable t)
      (throw t))))

(defn extract-string
  [map-or-str k]
  (let [value (when (map? map-or-str)
                (get map-or-str k))]
    (cond
      (nil? value)
      nil

      (string? value)
      (if (string/blank? value)
        nil
        value)

      :else
      (str value))))

(def ^:private *active-streams (atom 0))

;; This is extracted as a separate function mainly to support advanced
;; users who want to rebind it during tests. Note to those that do so:
;; the function is private to indicate that the contract may break in
;; future revisions. Use at your own risk. If you find yourself using
;; this to see what data is being put on `event-channel` consider
;; instead modifying your application's stream-ready-fn to support the
;; tests you want to write.
(defn- start-dispatch-loop
  "Kicks off the loop that transfers data provided by the application
  on `event-channel` to the HTTP infrastructure via
  `response-channel`."
  [{:keys [event-channel response-channel heartbeat-delay on-client-disconnect]}]
  (metrics/gauge ::active-streams nil #(deref *active-streams))
  (go
    (swap! *active-streams inc)
    (try
      (loop []
        (let [hb-timeout (timeout (* 1000 heartbeat-delay))
              [event port] (alts! [event-channel hb-timeout])]
          (cond
            (= port hb-timeout)
            (if (>! response-channel EOL)
              (recur)
              (log/info :msg "Response channel was closed when sending heartbeat. Shutting down SSE stream."))

            (and (some? event) (= port event-channel))
            ;; You can name your events using the maps
            ;; {:name "my-event" :data "some message data here"}
            ;; .. and optionally supply IDs (strings) that make sense to your application
            ;; {:name "my-event" :data "some message data here" :id "1234567890ae"}
            (let [event-name (extract-string event :name)
                  event-data (str
                               (if (map? event) (:data event) event))
                  event-id   (extract-string event :id)]
              (if (send-event response-channel event-name event-data event-id)
                (recur)
                (log/info :msg "Response channel was closed when sending event. Shutting down SSE stream.")))

            :else
            (log/info :msg "Event channel has closed. Shutting down SSE stream."))))
      (finally
        (close! event-channel)
        (close! response-channel)
        (swap! *active-streams dec)
        (when on-client-disconnect (on-client-disconnect))))))

;; Note: this is an odd mix of positional and named arguments but can't be easily fixed due to
;; backwards compatibility issues. Maybe deprecate it and provide something simpler.

(defn start-stream
  "Starts an SSE event stream and initiates a heartbeat to keep the
  connection alive. `stream-ready-fn` will be called with a core.async
  channel. The application can then put values on that channel to cause SSE events to be sent to the client.

  Values are either maps or simple values; maps will contain keys :name, :data, and :id.
  These are converted to UTF-8 strings and sent to the client.

  Non-map values are treated as if a map with just a :data key; the value is converted to a string
  and sent to the client.

  If the data value spans multiple lines, then the event will repeat the data field once for
  each line.

  Either the client or the application may close the channel to terminate and
  clean up the event stream; the client closes it by closing the
  connection.

  The SSE's core.async buffer can either be a fixed buffer (n) or a 0-arity
  function that returns a buffer.

  Arguments:
  - stream-ready-fn: passed the channel on which events may be conveyed, and the context
  - context - interceptor context
  - heartbeat-delay: time, in seconds, between heartbeats (defaults to 1)
  - bufferfn-or-n: a channel buffer size, or a no-args function that returns a buffer or buffer size

  Options:
  :on-client-disconnect - callback passed the context when the client disconnects.

  Returns the context with a :response key, and a :response-channel key (the channel to which events
  may be written)."
  ([stream-ready-fn context]
   (start-stream stream-ready-fn context 1))
  ([stream-ready-fn context heartbeat-delay]
   (start-stream stream-ready-fn context heartbeat-delay 10))
  ([stream-ready-fn context heartbeat-delay bufferfn-or-n]
   (start-stream stream-ready-fn context heartbeat-delay bufferfn-or-n {}))
  ([stream-ready-fn context heartbeat-delay bufferfn-or-n opts]
   (let [{:keys [on-client-disconnect]} opts
         response-channel     (chan (if (fn? bufferfn-or-n) (bufferfn-or-n) bufferfn-or-n))
         response             (-> (ring-response/response response-channel)
                                  (ring-response/content-type "text/event-stream")
                                  (ring-response/charset "UTF-8")
                                  (ring-response/header "Connection" "close")
                                  (ring-response/header "Cache-Control" "no-cache")
                                  (update :headers merge (:cors-headers context)))
         event-channel        (chan (if (fn? bufferfn-or-n) (bufferfn-or-n) bufferfn-or-n))
         context*             (assoc context
                                     :response-channel response-channel
                                     :response response)
         response-commited-ch (get-in context [:request :io.pedestal.http.request/response-commited-ch])]
     (thread
       (when response-commited-ch
         (<!! response-commited-ch))
       (stream-ready-fn event-channel context*)
       (start-dispatch-loop (merge {:event-channel    event-channel
                                    :response-channel response-channel
                                    :heartbeat-delay  heartbeat-delay
                                    :context          context*}
                                   (when on-client-disconnect
                                     {:on-client-disconnect #(on-client-disconnect context*)}))))
     context*)))

(defn ^{:deprecated "0.8.0"} start-event-stream
  "Returns an interceptor which will start a Server Sent Event stream
  with the requesting client, and set the ServletResponse to go
  async. After the request handling context has been paused in the
  Servlet thread, `stream-ready-fn` will be called in a future, with
  the resulting context from setting up the SSE event stream.

  opts is a map with optional keys:

  :on-client-disconnect - A function of one argument which will be
    called when the client permanently disconnects.

  DEPRECATED: Invoke start-stream from your own interceptor."
  ([stream-ready-fn]
   (start-event-stream stream-ready-fn 10 10))
  ([stream-ready-fn heartbeat-delay]
   (start-event-stream stream-ready-fn heartbeat-delay 10))
  ([stream-ready-fn heartbeat-delay bufferfn-or-n]
   (start-event-stream stream-ready-fn heartbeat-delay bufferfn-or-n {}))
  ([stream-ready-fn heartbeat-delay bufferfn-or-n opts]
   (i/deprecated `start-event-stream :in "0.8.0")
   (interceptor/interceptor
     {:name  (keyword (str (gensym "io.pedestal.http.sse/start-event-stream")))
      :enter (fn [context]
               (log/trace :msg "switching to sse")
               (start-stream stream-ready-fn context heartbeat-delay bufferfn-or-n opts))})))

(defn ^{:deprecated "0.7.0"} sse-setup
  "See start-event-stream. This function is for backward compatibility."
  [& args]
  (i/deprecated `sse-setup :in "0.7.0")
  (apply start-event-stream args))
