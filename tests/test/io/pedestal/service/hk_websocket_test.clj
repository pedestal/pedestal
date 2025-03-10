; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.hk-websocket-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.core.async :refer [put! >!! close! chan]]
            io.pedestal.http.http-kit
            [io.pedestal.http.route.definition.table :as table]
            [matcher-combinators.matchers :as m]
            [io.pedestal.async-events :as async-events :refer [write-event expect-event <event!! available-events!]]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.test-common :as tc :refer [<!!?]]
            [hato.websocket :as ws]
            [io.pedestal.service.websocket :as websocket]
            [io.pedestal.connector :as connector])
  (:import (java.nio ByteBuffer)))


(use-fixtures :once tc/instrument-specs-fixture)

(use-fixtures :each
              async-events/events-chan-fixture)

(def default-ws-opts
  {:on-open   (fn on-open [channel _request]
                (write-event :open channel)
                nil)
   :on-close  (fn on-close [_channel _proc reason]
                (write-event :close reason))
   :on-text   (fn on-text [_channel _proc text]
                (write-event :text text))
   :on-binary (fn on-binary [_channel _proc buffer]
                (write-event :binary buffer))})

(def echo-prefix
  (interceptor
    {:name  ::echo-prefix
     :enter (fn [context]
              (let [prefix (get-in context [:request :path-params :prefix])]
                (websocket/upgrade-request-to-websocket
                  context
                  (assoc default-ws-opts
                         :on-text (fn [channel _ text]
                                    (write-event :server-text text)
                                    (websocket/send-text! channel (str prefix " " text)))))))}))

(defn- reverse-bytes [^ByteBuffer buf]
  (let [limit  (.limit buf)
        result (ByteBuffer/allocate limit)]
    (dotimes [i limit]
      (.put result ^byte (.get buf (int (- limit i 1)))))
    (.flip result)
    result))

(def byte-reverser
  (websocket/websocket-interceptor
    ::byte-reverser
    (assoc default-ws-opts
           :on-binary (fn [channel _ data]
                        (write-event :server-binary data)
                        (websocket/send-binary! channel (reverse-bytes data))))))

(def countdown
  (websocket/websocket-interceptor
    ::countdown
    (assoc default-ws-opts
           :on-open (fn [ws-channel _]
                      (websocket/start-ws-connection ws-channel nil))
           :on-text (fn [_ ch text]
                      (let [count (parse-long text)]
                        (dotimes [i count]
                          (put! ch
                                (str (- count i)))))
                      (put! ch "Launch!")))))
(def oneshot
  (websocket/websocket-interceptor
    ::oneshot
    (assoc default-ws-opts
           :on-text (fn [conn _ text]
                      (websocket/send-text! conn (str "oneshot: " text))
                      (websocket/close! conn)))))

(def routes
  (table/table-routes
    [["/ws/echo/:prefix" :get echo-prefix]
     ["/ws/reverser" :get byte-reverser]
     ["/ws/countdown" :get countdown]
     ["/ws/oneshot" :get oneshot]]))

(def ws-uri "ws://localhost:8080")

(defmacro with-connector
  [routes & body]
  `(let [conn# (-> (connector/default-connector-map 8080)
                   (connector/with-default-interceptors)
                   (connector/with-routing :sawtooth ~routes)
                   (io.pedestal.http.http-kit/create-connector nil))]
     (try
       (connector/start! conn#)
       (do ~@body)
       (finally
         (connector/stop! conn#)))))

(deftest send-and-receive-text
  (with-connector routes
    (let [session @(ws/websocket (str ws-uri "/ws/echo/back") {:on-message (fn [_ text _]
                                                                             (write-event :client-text text))})]

      (expect-event :open)

      (ws/send! session "scratch")

      (is (= [:server-text "scratch"]
             (<event!!)))

      ;; Actually get back a CharBuffer, but that's not important.

      (is (match? [(m/via str "back scratch")]
                  (expect-event :client-text)))

      (ws/send! session "rub")

      (is (match? [(m/via str "back rub")]
                  (expect-event :client-text))))))

(defn- as-buffer
  ^ByteBuffer [^String s]
  (ByteBuffer/wrap (.getBytes s "UTF-8")))

(defn as-string
  [^ByteBuffer buf]
  (let [array (byte-array (.limit buf))]
    (.get buf array)
    (String. array "UTF-8")))

(deftest send-and-receive-binary
  (let [client-string "The sky above the port was the color of television, tuned to a dead channel."
        client-binary (as-buffer client-string)]
    (with-connector routes
      (let [session @(ws/websocket (str ws-uri "/ws/reverser") {:on-message (fn [_ data _]
                                                                              (write-event :client-binary data))})]

        (ws/send! session client-binary)

        ;; Server sees the binary message from client:

        (is (match? [(m/via as-string client-string)]
                    (expect-event :server-binary)))

        ;; Client sees the reversed binary message from the server:
        (is (match? [(m/via as-string (string/reverse client-string))]
                    (expect-event :client-binary)))))))

(deftest start-a-connection
  (with-connector routes
    (let [session @(ws/websocket (str ws-uri "/ws/countdown") {:on-message (fn [_ data _]
                                                                             (write-event :response data))})]


      (ws/send! session "2")

      (is (match? [(m/via str "2")]
                  (expect-event :response)))
      (is (match? [(m/via str "1")]
                  (expect-event :response)))
      (is (match? [(m/via str "Launch!")]
                  (expect-event :response))))))


(deftest server-closes-channel
  (with-connector routes
    (let [session @(ws/websocket (str ws-uri "/ws/oneshot") {:on-message (fn [_ text _]
                                                                           (write-event :client-text text))})]

      (ws/send! session "xyzzyx")

      (let [events (available-events!)]
        ;; May get these in some other order.
        (is (match? (m/embeds
                      [[:client-text (m/via str "oneshot: xyzzyx")]
                       ;; Note: differs from Jetty
                       [:close :server-close]])
                    events))))))

(defn- mock-ws-channel
  [acceptor *closed?]
  (reify websocket/WebSocketChannel

    (send-text! [_ message]
      (acceptor [:text message]))

    (send-binary! [_ message]
      (acceptor [:binary message]))

    (close! [_]
      (when *closed?
        (reset! *closed? true)))))

(defn- base-acceptor
  [*messages]
  (fn [event]
    (swap! *messages conj event)
    true))


(deftest ws-connection-closes-when-channel-closes
  (let [*messages      (atom [])
        *closed?       (atom false)
        channel        (mock-ws-channel (base-acceptor *messages) *closed?)
        binary-message (.getBytes "Binary Message" "utf-8")
        conn           (websocket/start-ws-connection channel nil)]
    (>!! conn "text message")
    (>!! conn binary-message)
    (Thread/sleep 100)
    (close! conn)

    (is (match? [[:text "text message"]
                 [:binary binary-message]]
                @*messages))

    (is (= true @*closed?))))

(deftest can-send-result-to-channel
  (let [*messages      (atom [])
        *closed?       (atom false)
        channel        (mock-ws-channel (base-acceptor *messages) *closed?)
        binary-message (.getBytes "Binary Message" "utf-8")
        bin-result-ch  (chan 1)
        conn           (websocket/start-ws-connection channel nil)]
    (>!! conn "text message")
    (>!! conn [binary-message bin-result-ch])

    (is (= :success (<!!? bin-result-ch)))

    (close! conn)

    (is (match? [[:text "text message"]
                 [:binary binary-message]]
                @*messages))

    (is (= true @*closed?))))

(deftest connection-closes-when-send-fails!
  (let [*messages (atom [])
        *closed?  (atom false)
        acceptor  (fn [event]
                    (swap! *messages conj event)
                    (= "1" (second event)))
        channel   (mock-ws-channel acceptor *closed?)
        conn      (websocket/start-ws-connection channel nil)
        result-ch (chan 1)]
    (>!! conn "1")
    (>!! conn ["2" result-ch])
    (>!! conn "3")

    (= :closed (<!!? result-ch))

    (is (match? [[:text "1"]
                 [:text "2"]]
                @*messages))

    ;; The connection (send-ch) closes, but the WebSocketChannel does not.

    (is (= false @*closed?))))

(deftest thrown-exception-is-reported-and-does-not-kill-loop
  (let [*messages (atom [])
        *closed?  (atom false)
        e         (IllegalStateException.)
        acceptor  (fn [[_ data :as event]]
                    (swap! *messages conj event)
                    (when (= data "2")
                      (throw e))
                    true)
        channel   (mock-ws-channel acceptor *closed?)
        conn      (websocket/start-ws-connection channel nil)
        ch-2 (chan 1)
        ch-3 (chan 1)]
    (>!! conn "1")
    (>!! conn ["2" ch-2])

    (is (= e (<!!? ch-2)))

    (>!! conn ["3" ch-3])

    (is (= :success (<!!? ch-3)))

    (is (match? [[:text "1"]
                 [:text "2"]
                 [:text "3"]]
             @*messages))

    (is (= false @*closed?))))


