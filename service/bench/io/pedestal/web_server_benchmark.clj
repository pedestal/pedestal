(ns io.pedestal.web-server-benchmark
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.servlet :as servlet]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.interceptor :as interceptor :refer (defbefore)]
            [io.pedestal.http.immutant :as immutant]
            [io.pedestal.http.jetty :as jetty])
  (:import (java.nio ByteBuffer))
  (:gen-class))

;; -----------------------
;; !!!!  ATTENTION !!!!!
;; =======================
;; To everyone looking here, please don't ever write Pedestal code like this
;;  - Paul


;; General issues with this benchmark
;; ----------------------------------
;;
;; * These benchmarks don't exercise a full-application.
;;     They are lacking routing, complex result building,
;;     advanced platform features, compression, etc.
;; * No one writes code like this.
;;     Applications don't return strings/arrays/buffers of info stored in memory.
;;     The workload of this benchmark is unrealistic.
;; * The benchmark encourages poorly-conceived synchronous sever-side operations
;;     The benchmark is only testing a synchronous operation,
;;     but most well-designed and featureful servers/services need robust async
;;     operations.
;; * Everyone is "cheating"; The results are misleading.
;;     Nearly all of the implementations are creating a compiled servlet
;;     of *exactly* the static response.  No one ever uses these libraries
;;     like that, even in high-performance settings.  Why are we using a
;;     using a benchmark that optimizes a case no one uses?  That's misleading.


;; Another proposed benchmark
;; ---------------------------
;;
;; I propose another benchmark for comparing web service libraries.
;; Your service must support two endpoints:
;;  * `/response` - A static, in memory response in `text/plain`
;;  * `/proxy` - A proxying-call to another service's `/response`
;;
;; In the benchmark, two instances of your service are deployed, running on
;; different ports - we'll call them 'A' and 'B'.
;; All requests are directed to one server, 'A'.
;; 50% of requests will be for `/response`, and 50% of requests will be for `/proxy`.
;; A's `/proxy` call *MUST* be a forwarding-proxy to B's `/response`, NO REDIRECTS
;; You must use one of the supplied http clients (one sync, one async).
;;
;; ### Why?
;; We often write services that call other services.  That's a fair benchmark.
;; All of our services do routing, our benchmark should exercise routing.
;; Forcing the service to do a forwarding-proxy tests the libraries' capabilities
;; and exercises advanced platform features within each tool/library.
;;
;; This is the benchmark that the Pedestal team uses - please see `niotooling`

;; ----
;; Regardless, here is the "shootout" benchmark.
;; Feel free to try the String responses instead of ByteBuffer responses.
;; ----

(def bb-response ^ByteBuffer (ByteBuffer/wrap (.getBytes ^String (slurp "../index.html") "UTF-8")))
(def response {:status 200
               :headers {"Content-Type" "text/html"}
               :body bb-response})

(defbefore h
  "A static NIO response."
  [ctx]
  (.rewind bb-response)
  (assoc ctx :response response))

(def server (immutant/server
              (servlet/servlet :service (servlet-interceptor/http-interceptor-service-fn [h]))
              {:port 8088}))

(defn -main [& args]
  (println "Starting...")
  ((:start-fn server)))

