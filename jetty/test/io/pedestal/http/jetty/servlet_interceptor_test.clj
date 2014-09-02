(ns io.pedestal.http.jetty.servlet-interceptor-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [clj-http.client :as http]
            [io.pedestal.http :as server]
            [io.pedestal.http.route.definition :refer [defroutes]])
  (:import (java.nio channels.Pipe
                     ByteBuffer)))


(defn echo [request]
  (let [bb (ByteBuffer/wrap (.getBytes "Hello World"))
        pipe (Pipe/open)
        source (.source pipe)
        sink (.sink pipe)]
    (a/thread
     (.write sink bb)
     (.close sink))
    {:status 200
     :body source
     :headers {"Content-Type" "text/plain"}}))

(defroutes routes
  [[["/" {:get echo}]]])

(def service
  {:env :prod
   ::server/routes routes
   ::server/type :jetty
   ::server/port 8081})

(defmacro with-service [service & body]
  `(let [service# (server/create-server service)]
     (def ~'created-service service#)
     (try
       (future (server/start service#))
       (Thread/sleep 1000)
       ~@body
       (finally
         (server/stop service#)))))

;; simple functional test to prove NIO over jetty works
(deftest nio-works
  (with-service service
    (let [resp (http/get "http://127.0.0.1:8081/")]
      (is (= 200 (:status resp)))
      (is (= "Hello World" (:body resp))))))
