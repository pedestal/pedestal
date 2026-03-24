;; tag::ns[]
(ns sse-demo
  (:require [clojure.core.async :as async]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.http-kit :as hk]
            [io.pedestal.http.sse :as sse]
            [io.pedestal.interceptor :as interceptor]))
;; end::ns[]

;; tag::home-page[]
(defn home-page
  [_request]
  {:status 200 :body "Hello, World!"})
;; end::home-page[]

;; tag::stream-ready[]
(defn stream-ready [event-chan context]
  (future                                                   ;; <1>
    (dotimes [i 10]
      (async/>!! event-chan {:name "counter" :data i})       ;; <2>
      (Thread/sleep 1000))
    (async/close! event-chan)))                              ;; <3>
;; end::stream-ready[]

;; tag::counter-interceptor[]
(def counter-interceptor
  (interceptor/interceptor
    {:name  ::counter
     :enter (fn [context]
              (sse/start-stream stream-ready context))}))    ;; <1>
;; end::counter-interceptor[]

;; tag::routes[]
(def routes
  #{["/"        :get home-page]
    ["/counter" :get counter-interceptor]})
;; end::routes[]

;; tag::connector[]
(defn create-connector []
  (-> (conn/default-connector-map 8890)
      (conn/with-default-interceptors)
      (conn/with-routes routes)
      (hk/create-connector nil)))

(defonce *connector (atom nil))

(defn start []
  (reset! *connector (conn/start! (create-connector))))

(defn stop []
  (conn/stop! @*connector)
  (reset! *connector nil))

(defn restart []
  (when @*connector (stop))
  (start))
;; end::connector[]
