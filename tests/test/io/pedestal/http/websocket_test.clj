(ns io.pedestal.http.websocket-test
  (:require
    [clojure.test :refer [deftest is use-fixtures report]]
    [hato.websocket :as ws]
    [io.pedestal.http :as http]
    [clojure.core.async :refer [chan put!] :as async]
    [io.pedestal.http.jetty.websockets :as websockets])
  (:import (java.nio ByteBuffer)))

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
    events-chan ([status-value] status-value)

    (async/timeout 75) [::timed-out]))

(defmacro expect-event
  "Expects a particular kind of event value (:connect, :close, :text, etc.).
  Ignores other statuses until a match is found, or a timeout occurs.
  Reports a failure on timeout.

  Returns the event value on success, or nil on failure."
  [expected-kind]
  `(let [expected-kind# ~expected-kind]
     (loop [skipped# []]
       (let [[kind# :as event#] (<event!!)]
         (cond
           (= kind# expected-kind#)
           (do
             (report {:type :pass})
             event#)

           (= ::timed-out kind#)
           (do
             (report {:type :fail
                      :message "Expected event was not delivered"
                      :expected expected-kind#
                      :actual (conj skipped# event#)})
             nil)

           :else
           (recur (conj skipped# event#)))))))

(def default-ws-handlers
  ;; Also called an "action map"
  {:on-connect #(put! events-chan [:connect %])
   :on-close (fn [status-code reason]
               (put! events-chan [:close status-code reason]))
   :on-error #(put! events-chan [:error %])
   :on-text #(put! events-chan [:text %])
   :on-binary (fn [payload offset length]
                (put! events-chan [:binary payload offset length]))})

(def default-ws-map {"/ws" default-ws-handlers})

(defn ws-server
  [ws-map]
  (http/create-server {::http/type :jetty
                       ::http/join? false
                       ::http/port 8080
                       ::http/routes []
                       ::http/container-options
                       {:context-configurator #(websockets/add-ws-endpoints % ws-map)}}))

(defmacro with-server
  [ws-map & body]
  `(let [server# (ws-server ~ws-map)]
     (try
       (http/start server#)
       (do ~@body)
       (finally
         (http/stop server#)))))

(deftest client-sends-text
  (with-server default-ws-map
    (let [session @(ws/websocket ws-uri {})]
      (expect-event :connect)
      (ws/send! session "hello")

      (is (= [:text "hello"]
             (<event!!)))

      ;; Note: the status code value is tricky, must be one of a few preset values, or in the
      ;; range 3000 to 4999.
      @(ws/close! session 4000 "A valid reason")

      (is (= [:close 4000 "A valid reason"]
             (<event!!))))))

(deftest client-sends-binary
  (with-server default-ws-map
    (let [session @(ws/websocket ws-uri {})
          buffer-bytes (.getBytes "A mind forever voyaging" "utf-8")
          buffer (ByteBuffer/wrap buffer-bytes)]

      (ws/send! session buffer)
      (.rewind buffer)

      (when-let [[_ b o l] (expect-event :binary)]
        (let [buffer' (ByteBuffer/wrap b o l)]
          (is (= buffer buffer')))))))

(defn- conversation-actions
  [action-map]
  (let [*send-ch (atom nil)
        on-connect (websockets/start-ws-connection
                     (fn [_ send-ch]
                       (reset! *send-ch send-ch)))
        {:keys [on-text on-binary]} action-map]
    (cond-> (assoc action-map :on-connect on-connect)
            on-text (assoc :on-text (fn [text]
                                      (on-text @*send-ch text)))
            on-binary (assoc :on-binary (fn [byte-array offset length]
                                          (on-binary @*send-ch (ByteBuffer/wrap byte-array offset length)))))))

(deftest text-conversation
  (with-server {"/ws" (conversation-actions {:on-text (fn [send-ch text]
                                                        (put! events-chan [:text text])
                                                        (put! send-ch (str "Hello, " text)))})}
    (let [session @(ws/websocket ws-uri {:on-message (fn [ws data last?]
                                                       (put! events-chan [:client-message data last?]))})]
      (ws/send! session "Bob")
      (is (= [:text "Bob"]
             (expect-event :text)))

      (when-let [[_ data last] (expect-event :client-message)]
        (is (= "Hello, Bob" (str data)))
        (is (true? last))))))

(deftest text-conversation
  (let [response-buffer (ByteBuffer/wrap (.getBytes "We Agree" "utf-8"))]
    (with-server {"/ws" (conversation-actions {:on-binary (fn [send-ch data]
                                                            (put! events-chan [:binary data])
                                                            (put! send-ch response-buffer))})}
      (let [buffer (ByteBuffer/wrap (.getBytes "WebSockets are nifty" "utf-8"))
            session @(ws/websocket ws-uri {:on-message (fn [ws data last?]
                                                         (put! events-chan [:client-message data last?]))})]
        (ws/send! session buffer)
        (.rewind buffer)

        (when-let [[_ received-buffer] (expect-event :binary)]
          (is (= buffer received-buffer)))

        (when-let [[_ data last] (expect-event :client-message)]
          (.rewind response-buffer)
          (is (= response-buffer data))
          (is (true? last)))))))

;; TODO: test when server closes, client sees it
;; TODO: test error callback



