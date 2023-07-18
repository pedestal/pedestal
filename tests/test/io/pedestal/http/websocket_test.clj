(ns io.pedestal.http.websocket-test
  (:require
    [clojure.test :refer [deftest is use-fixtures report]]
    [hato.websocket :as ws]
    [io.pedestal.http :as http]
    [clojure.core.async :refer [chan put!] :as async]
    [io.pedestal.http.jetty.websockets :as websockets])
  (:import (java.nio ByteBuffer)))

(def ws-uri "ws://localhost:8080/ws")

(def server-status-chan nil)

(defn server-status-chan-fixture [f]
  (with-redefs [server-status-chan (chan 10)]
    (f)))

(use-fixtures :each
              server-status-chan-fixture)

(defn <status!!
  []
  (async/alt!!
    server-status-chan ([status-value] status-value)

    (async/timeout 75) [::timed-out]))

(defmacro expect-status
  "Expects a particular kind of status value (:connect, :close, :text, etc.).
  Ignores other statuses until a match is found, or a timeout occurs.
  Reports a failure on timeout.

  Returns the status value on success, or nil on failure."
  [expected-kind]
  `(let [expected-kind# ~expected-kind]
     (loop [skipped# []]
       (let [[kind# :as status-value#] (<status!!)]
         (cond
           (= kind# expected-kind#)
           (do
             (report {:type :pass})
             status-value#)

           (= ::timed-out kind#)
           (do
             (report {:type :fail
                      :message "Expected server status was not delivered"
                      :expected expected-kind#
                      :actual skipped#})
             nil)

           :else
           (recur (conj skipped# status-value#)))))))

(def default-ws-handlers
  ;; Also called an "action map"
  {:on-connect #(put! server-status-chan [:connect %])
   :on-close (fn [status-code reason]
               (put! server-status-chan [:close status-code reason]))
   :on-error #(put! server-status-chan [:error %])
   :on-text #(put! server-status-chan [:text %])
   :on-binary (fn [payload offset length]
                (put! server-status-chan [:binary payload offset length]))})

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
      (expect-status :connect)
      (ws/send! session "hello")

      (is (= [:text "hello"]
             (<status!!)))

      ;; Note: the status code value is tricky, must be one of a few preset values, or in the
      ;; range 3000 to 4999.
      @(ws/close! session 4000 "A valid reason")

      (is (= [:close 4000 "A valid reason"]
             (<status!!))))))

(deftest client-sends-binary
  (with-server default-ws-map
    (let [session @(ws/websocket ws-uri {})
          buffer-bytes (.getBytes "A mind forever voyaging" "utf-8")
          buffer (ByteBuffer/wrap buffer-bytes)]

      (ws/send! session buffer)
      (.rewind buffer)

      (when-let [[_ b o l] (expect-status :binary)]
        (let [buffer' (ByteBuffer/wrap b o l)]
          (is (= buffer buffer')))))))

;; TODO: test text round-trip
;; TODO: test binary round-trip
;; TODO: test when server closes, client sees it
;; TODO: test error callback



