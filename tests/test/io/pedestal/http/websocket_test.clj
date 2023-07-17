(ns io.pedestal.http.websocket-test
  (:require
   [clojure.test :refer :all]
   [hato.websocket :as ws]
   [io.pedestal.http]
   [io.pedestal.http.jetty.websockets :as websockets])
  (:import (org.eclipse.jetty.servlet ServletContextHandler)))

(def status (atom nil))

(defn server-fixture [f]
  (reset! status nil)
  (let [ws-map  {"/ws" {:on-connect #(swap! status assoc :session %)
                        :on-close #(swap! status merge {:status-code %1
                                                        :reason %2})
                        :on-error #(swap! status assoc :cause %)
                        :on-text #(swap! status assoc :string %)
                        :on-binary #(swap! status merge {:payload %1
                                                         :offset %2
                                                         :len %3})}}
        options {:context-configurator (fn [^ServletContextHandler h]
                                         (websockets/add-ws-endpoints h
                                                                      ws-map))}
        server  (io.pedestal.http/create-server (merge {:io.pedestal.http/type :jetty
                                                        :io.pedestal.http/join? false
                                                        :io.pedestal.http/port 8080
                                                        :io.pedestal.http/routes []
                                                        :io.pedestal.http/container-options options}))]
    (try
      (io.pedestal.http/start server)
      (f)
      (finally (io.pedestal.http/stop server)))))

(use-fixtures :each server-fixture)

;; TODO: test text round-trip
(deftest client-sends-text-test
  (let [session @(ws/websocket "ws://localhost:8080/ws" {})]
    @(ws/send! session "hello")
    (Thread/sleep 300)
    (is (= (:string @status) "hello"))))

;; TODO: test binary round-trip
;; TODO: test when server closes, client sees it
;; TODO: test when client closes, server sees it
