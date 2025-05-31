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

(ns io.pedestal.http.sse-test
  (:require [io.pedestal.http.http-kit :as hk]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.connector :as conn]
            [clojure.core.async :refer [go >! <! timeout close! chan put!]]
            [io.pedestal.http.sse :as sse :refer [start-event-stream]]
            [io.pedestal.test-common :refer [<!!?]]
            [clojure.test :refer [deftest is]]
            [io.pedestal.http.cors :as cors])
  (:import (clojure.core.async.impl.protocols Channel)
           (cloud.prefab.sse SSEHandler)
           (cloud.prefab.sse.events DataEvent)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse HttpResponse$BodyHandlers)
           (java.time Duration)
           (java.util.concurrent Flow$Subscriber Flow$Subscription)))

(deftest sse-start-stream
  (let [fake-context        {:request {:headers {"origin" "http://foo.com:8080"}}}
        interceptor-context (chain/enqueue* fake-context
                                            (cors/allow-origin ["http://foo.com:8080"])
                                            ;; The `stream-ready-fn` takes the channel and the context
                                            (start-event-stream (fn [ch _context] ch)))
        {{body                                          :body
          {content-type  "Content-Type"
           connection    "Connection"
           cache-control "Cache-Control"
           allow-origin  "Access-Control-Allow-Origin"} :headers
          status                                        :status} :response}
        (chain/execute interceptor-context)]
    (is body "Response has a body")
    (is (instance? Channel body) "Response body is a channel")
    (is (= 200 status)
        "A successful status code is sent to the client.")
    (is (= "text/event-stream; charset=UTF-8" content-type)
        "The mime type and character encoding are set with the servlet setContentType method")
    (is (= "close" connection)
        "The client is instructed to close the connection.")
    (is (= "no-cache" cache-control)
        "The client is instructed not to cache the event stream")
    (is (= "http://foo.com:8080" allow-origin)
        "The origin is allowed")))

(def count-interceptor
  (interceptor/interceptor
    {:name  ::count
     :enter (fn [context]
              (let [path-params (get-in context [:request :path-params])
                    n           (-> path-params :count parse-long)
                    id          (:id path-params)
                    process-fn  (fn [ch _context]
                                  (go
                                    (dotimes [i n]
                                      (<! (timeout 100))
                                      (>! ch {:name "count"
                                              :data (str (inc i) "...")
                                              :id   id}))
                                    (<! (timeout 100))
                                    (close! ch)))]
                (sse/start-stream process-fn
                                  context
                                  1)))}))

(def ticker-interceptor
  (interceptor/interceptor
    {:name  ::ticker
     :enter (fn [context]
              (let [process-fn (fn [ch _context]
                                 (go
                                   (doseq [t ["NU" "IBM" "AMZ"]]
                                     (<! (timeout 100))
                                     (>! ch t))
                                   (<! (timeout 100))
                                   (close! ch)))]
                (sse/start-stream process-fn
                                  context
                                  1
                                  ;; A function that returns the buffer size (just to get code coverage)
                                  (constantly 10))))}))

(def multi-line-interceptor
  (interceptor/interceptor
    {:name  ::multi-line
     :enter (fn [context]
              (let [process-fn (fn [ch _context]
                                 (put! ch
                                       "Choose immutability\nand see where that takes you.")
                                 (close! ch))]
                (sse/start-stream process-fn
                                  context
                                  1
                                  ;; A function that returns the buffer size (just to get code coverage)
                                  (constantly 10))))}))

(defn sse-session
  [url]
  (let [ch         (chan 5)
        handler    (SSEHandler.)
        httpClient (HttpClient/newHttpClient)
        subscriber (reify Flow$Subscriber

                     (onSubscribe [_ subscription]
                       (.request subscription Long/MAX_VALUE))

                     ;; Normally, might want to check for comment events
                     ;; But Pedestal doesn't even send those.
                     (onNext [_ data-event]
                       (put! ch [:message {:name (.getEventName ^DataEvent data-event)
                                           :data (.getData ^DataEvent data-event)
                                           :id   (.getLastEventId ^DataEvent data-event)}]))

                     (onError [_ error]
                       (put! ch [:error error])
                       (close! ch))

                     (onComplete [_]
                       (put! ch [:complete])
                       (close! ch)))
        request    (-> (HttpRequest/newBuilder)
                       (.header "Accept" SSEHandler/EVENT_STREAM_MEDIA_TYPE)
                       (.timeout (Duration/ofSeconds 100))
                       (.uri (URI/create url))
                       .build)
        _          (.subscribe handler subscriber)
        result     (.send httpClient request (HttpResponse$BodyHandlers/fromLineSubscriber handler))]
    (is (= 200
           (.statusCode ^HttpResponse result)))

    ;; Just a test, don't care that HttpClient is not properly closed.
    ch))


(defn- new-connector
  []
  (-> (conn/default-connector-map 9876)
      (conn/with-interceptor cors/dev-allow-origin)
      conn/with-default-interceptors
      (conn/with-routes #{["/api/sse/:count/:id" :get count-interceptor]
                          ["/api/sse/ticker" :get ticker-interceptor]
                          ["/api/sse/multi" :get multi-line-interceptor]})))

(defn- end-to-end
  [id]
  (let [ch (sse-session (str "http://localhost:9876/api/sse/3/" id))]

    (is (match?
          [:message {:name "count"
                     :data "1...\n"
                     :id   id}] (<!!? ch)))

    (is (match?
          [:message {:name "count"
                     :data "2...\n"
                     :id   id}] (<!!? ch)))

    (is (match?
          [:message {:name "count"
                     :data "3...\n"
                     :id   id}] (<!!? ch)))

    (is (= [:complete] (<!!? ch)))

    ;; And the channel is closed
    (is (= nil (<!!? ch))))

  (let [ch (sse-session "http://localhost:9876/api/sse/ticker")]

    (is (match?
          [:message {:name "message"
                     :data "NU\n"
                     :id   ""}] (<!!? ch)))

    (is (match?
          [:message {:name "message"
                     :data "IBM\n"
                     :id   ""}] (<!!? ch)))

    (is (match?
          [:message {:name "message"
                     :data "AMZ\n"
                     :id   ""}] (<!!? ch)))

    (is (= [:complete] (<!!? ch)))

    ;; And the channel is closed
    (is (= nil (<!!? ch))))

  (let [ch (sse-session "http://localhost:9876/api/sse/multi")]

    (is (match?
          [:message {:data "Choose immutability\nand see where that takes you.\n"}]
          (<!!? ch)))


    (is (= [:complete] (<!!? ch)))

    ;; And the channel is closed
    (is (= nil (<!!? ch)))))

(deftest jetty-end-to-end
  (let [conn (-> (new-connector)
                 (jetty/create-connector nil)
                 (conn/start!))]
    (try
      (end-to-end "jetty12")
      (finally
        (conn/stop! conn)))))

(deftest hk-end-to-end
  (let [conn (-> (new-connector)
                 (hk/create-connector nil)
                 (conn/start!))]
    (try
      (end-to-end "hk2.9.0")
      (finally
        (conn/stop! conn)))))



