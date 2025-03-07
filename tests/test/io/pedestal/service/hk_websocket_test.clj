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
  (:require [clojure.test :refer [deftest is use-fixtures]]
            io.pedestal.http.http-kit
            [io.pedestal.http.route.definition.table :as table]
            [matcher-combinators.matchers :as m]
            [io.pedestal.async-events :as async-events :refer [write-event expect-event <event!!]]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.test-common :as tc]
            [hato.websocket :as ws]
            [io.pedestal.service.websocket :as websocket]
            [io.pedestal.connector :as connector]))


(use-fixtures :once tc/instrument-specs-fixture)

(use-fixtures :each
              async-events/events-chan-fixture)

(def default-endpoint-map
  {:on-open   (fn on-open [channel request]
                (write-event :open channel)
                nil)
   :on-close  (fn on-close [channel proc reason]
                (write-event :close reason))
   :on-text   (fn on-text [_channel _proc text]
                (write-event :text text))
   :on-binary (fn on-binary [_channel _proc buffer]
                (write-event :binary buffer))})

(def echo-prefix-interceptor
  (interceptor/interceptor
    {:name  ::echo-prefix
     :enter (fn [context]
              (let [prefix (get-in context [:request :path-params :prefix])]
                (websocket/upgrade-request-to-websocket
                  context
                  (assoc default-endpoint-map
                         :on-text (fn [channel _ text]
                                    (write-event :server-text text)
                                    (websocket/send-text! channel (str prefix " " text)))))))}))

(def routes
  (table/table-routes
    [["/ws/echo/:prefix" :get [echo-prefix-interceptor]]]))


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

(deftest basic-echo
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


