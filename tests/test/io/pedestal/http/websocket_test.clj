; Copyright 2024 Nubank NA
; Copyright 2023 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.websocket-test
  (:require
    [clojure.test :refer [deftest is use-fixtures report]]
    [hato.websocket :as ws]
    [io.pedestal.http :as http]
    [io.pedestal.http.jetty :as jetty]
    [clojure.core.async :refer [chan put! close!] :as async]
    [net.lewisship.trace :refer [trace]]
    [io.pedestal.websocket :as websocket])
  (:import (jakarta.websocket CloseReason CloseReason$CloseCodes)
    #_:clj-kondo/ignore
           (java.net.http WebSocket)
           (java.nio ByteBuffer)))

(def ws-uri "ws://localhost:8080/ws")

(def events-chan nil)

(defn events-chan-fixture [f]
  (with-redefs [events-chan (chan 10)]
    (f)))

(use-fixtures :each
              events-chan-fixture)

(defn <event!!
  []
  (async/alt!!
    events-chan ([status-value]
                 (trace :event status-value)
                 status-value)

    (async/timeout 75) [::timed-out]))

(defmacro expect-event
  "
  Events are expected to be a vector where the first element is the type (:open, :close, :text, etc.).
  Expects a particular kind of event to be in the channel.
  Consumes and ignores events until a match is found, or a timeout occurs.
  Reports a failure on timeout that includes any consumed events.

  Returns the rest of the event (i.e., the type is stripped out) on success, or nil on failure."
  [expected-kind]
  `(let [expected-kind# ~expected-kind]
     (loop [skipped# []]
       (let [[kind# :as event#] (<event!!)]
         (cond
           (= kind# expected-kind#)
           (do
             (report {:type :pass})
             (rest event#))

           (= ::timed-out kind#)
           (do
             (report {:type     :fail
                      :message  "Expected event was not delivered"
                      :expected expected-kind#
                      :actual   (conj skipped# event#)})
             nil)

           :else
           (recur (conj skipped# event#)))))))

(def default-endpoint-map
  {:on-open   (fn [session _config]
                (trace :in :on-open :session session :config _config)
                (put! events-chan [:open session])
                nil)
   :on-close  (fn [_ _session reason]
                (trace :in :on-close :session _session :reason reason)
                (put! events-chan [:close
                                   (-> reason .getCloseCode .getCode)
                                   (-> reason .getReasonPhrase)]))
   :on-error  (fn [_ _session exception]
                (put! events-chan [:error exception]))
   :on-text   (fn [_ text]
                (put! events-chan [:text text]))
   :on-binary (fn [_ buffer]
                (put! events-chan [:binary buffer]))})

(def default-websockets-map {"/ws" default-endpoint-map})

(defn ws-server
  [websockets]
  (http/create-server {::http/type       jetty/server
                       ::http/join?      false
                       ::http/port       8080
                       ::http/routes     []
                       ::http/websockets websockets}))

(defmacro with-server
  [ws-map & body]
  `(let [server# (ws-server ~ws-map)]
     (try
       (http/start server#)
       (do ~@body)
       (finally
         (http/stop server#)))))

(deftest configuration-options
  (let [*builder (atom nil)
        *session (atom nil)
        ws-map   (update default-websockets-map "/ws" assoc
                         :configure #(reset! *builder %)
                         :subprotocols ["sub" "protocols"]
                         :idle-timeout-ms 100000
                         :on-open (fn [session _config]
                                    (reset! *session session)
                                    (put! events-chan [:open session])))]
    (with-server ws-map
                 (let [config (-> *builder deref .build)]
                   (is (= "/ws" (.getPath config)))
                   (is (= ["sub" "protocols"]
                          (-> config .getSubprotocols vec))))

                 (let [session @(ws/websocket ws-uri {})]
                   (expect-event :open)
                   (ws/send! session "hello")

                   (let [session @*session]
                     (is (= 100000 (.getMaxIdleTimeout session))))))))

(deftest client-sends-text
  (with-server default-websockets-map
               (let [session @(ws/websocket ws-uri {})]
                 (expect-event :open)
                 (ws/send! session "hello")

                 (is (= [:text "hello"]
                        (<event!!)))

                 ;; Note: the status code value is tricky, must be one of a few preset values, or in the
                 ;; range 3000 to 4999.
                 @(ws/close! session 4000 "A valid reason")

                 (is (= [:close 4000 "A valid reason"]
                        (<event!!))))))

(deftest client-sends-binary
  (with-server default-websockets-map
               (let [session      @(ws/websocket ws-uri {})
                     buffer-bytes (.getBytes "A mind forever voyaging" "utf-8")
                     buffer       (ByteBuffer/wrap buffer-bytes)]

                 (ws/send! session buffer)
                 (.rewind buffer)

                 (when-let [[buffer'] (expect-event :binary)]
                   (is (= buffer buffer'))))))

(defn- conversation-actions
  [action-map]
  (assoc action-map :on-open (websocket/on-open-start-ws-connection nil)))

(deftest text-conversation
  (with-server {"/ws" (conversation-actions {:on-text (fn [send-ch text]
                                                        (put! events-chan [:text text])
                                                        (put! send-ch (str "Hello, " text)))})}
               (let [session @(ws/websocket ws-uri {:on-message (fn [_ws data last?]
                                                                  (put! events-chan [:client-message data last?]))})]
                 (ws/send! session "Bob")
                 (is (= ["Bob"]
                        (expect-event :text)))

                 (when-let [[data last] (expect-event :client-message)]
                   (is (= "Hello, Bob" (str data)))
                   (is (true? last))))))

(deftest binary-conversation
  (let [response-buffer (ByteBuffer/wrap (.getBytes "We Agree" "utf-8"))]
    (with-server {"/ws" (conversation-actions {:on-binary (fn [send-ch data]
                                                            (put! events-chan [:binary data])
                                                            (put! send-ch response-buffer))})}
                 (let [buffer  (ByteBuffer/wrap (.getBytes "WebSockets are nifty" "utf-8"))
                       session @(ws/websocket ws-uri {:on-message (fn [_ws data last?]
                                                                    (put! events-chan [:client-message data last?]))})]
                   (ws/send! session buffer)
                   (.rewind buffer)

                   (when-let [[received-buffer] (expect-event :binary)]
                     (is (= buffer received-buffer)))

                   (when-let [[data last] (expect-event :client-message)]
                     (.rewind response-buffer)
                     (is (= response-buffer data))
                     (is (true? last)))))))

(deftest closing-send-channel-shuts-down-connection
  (with-server {"/ws" (conversation-actions {:on-text  (fn [send-ch text]
                                                         (put! events-chan [:text text])
                                                         (when (= "DIE" text)
                                                           (close! send-ch)))
                                             :on-close (fn [_ _ ^CloseReason reason]
                                                         (trace :event :on-close
                                                                :reason reason)
                                                         (put! events-chan [:close (.getCloseCode reason)]))})}
               (let [session @(ws/websocket ws-uri {:on-message (fn [_ws data last?]
                                                                  (put! events-chan [:client-message data last?]))
                                                    :on-error   (fn [_ws err]
                                                                  ;; Doesn't get called when socket closed by server
                                                                  (trace :event :client-error
                                                                         :error err))
                                                    :on-close   (fn [_ws status-code reason]
                                                                  (trace :event :client-on-close
                                                                         :reason reason)
                                                                  ;; Client on-close handler does not appear to be
                                                                  ;; invoked.
                                                                  (put! events-chan [:client-close status-code reason]))})]
                 (ws/send! session "Bob")
                 (is (= ["Bob"]
                        (expect-event :text)))

                 (ws/send! session "DIE")

                 (is (= [CloseReason$CloseCodes/NORMAL_CLOSURE] (expect-event :close)))

                 ;; It appears that Hato fails to notify us when the web socket is closed by the server.
                 #_(is (= [WebSocket/NORMAL_CLOSURE nil] (expect-event :client-close))))))

(deftest exception-during-open-is-identified
  (let [e (RuntimeException. "on-open exception")]
    (with-server {"/ws" {:on-open  (fn [_session _config]
                                     (put! events-chan [:open])
                                     (throw e))
                         :on-error (fn [_ _ t]
                                     (put! events-chan [:error t]))
                         :on-close (fn [_ _ ^CloseReason reason]
                                     (put! events-chan [:close (.getCloseCode reason)]))}}
                 (let [_session @(ws/websocket ws-uri {})]
                   (expect-event :open)
                   (when-let [[t] (expect-event :error)]
                     (is (= e
                            ;; The actual exception gets wrapped a couple of times:
                            (-> t .getCause .getCause))))))))


(deftest exception-during-on-text-is-identified
  (let [e (RuntimeException. "on-text exception")]
    (with-server {"/ws" {:on-text  (fn [_ text]
                                     (put! events-chan [:text text])
                                     (throw e))
                         :on-error (fn [_ _ t]
                                     (put! events-chan [:error (ex-message t)]))
                         :on-close (fn [_ _ ^CloseReason reason]
                                     (put! events-chan [:close (-> (.getCloseCode reason) .getCode)
                                                        (.getReasonPhrase reason)]))}}
                 (let [session @(ws/websocket ws-uri {:on-error (fn [_ws t]
                                                                  (put! events-chan [:client-error t]))
                                                      :on-close (fn [_ws status-code reason]
                                                                  (put! events-chan [:client-close status-code reason]))})]
                   (ws/send! session "CHOKE")

                   (is (= ["CHOKE"]
                          (expect-event :text)))

                   (let [[message] (expect-event :error)]
                     (is (= "Endpoint notification error" message)))

                   (is (= [1003
                           "Endpoint notification error"]
                          (expect-event :close)))

                   ;; Looks like Jetty 11 doesn't pass this down
                   #_(is (= [1011 "on-text exception"]
                            (expect-event :client-close)))
                   ))))
