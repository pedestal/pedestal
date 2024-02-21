(ns metrics-playground
  (:require [io.pedestal.metrics :as m]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [net.lewisship.trace :refer [trace]]
            [io.pedestal.tracing :as t])
  (:import (io.opentelemetry.context Context)))


(defonce server nil)


(defn status-handler
  [request]
  (trace :request request)
  {:status  200
   :headers {}
   :body    {:state :running}})

(defn- create-and-start-server
  [_]
  (->> {::http/port   8080
        ::http/type   :jetty
        ::http/join?  false
        ::http/routes (route/routes-from #{["/status" :get status-handler :route-name ::status]})}
       http/create-server
       http/start))

(defn start
  []
  (if server
    :already-started
    (do
      (alter-var-root #'server create-and-start-server)
      :ok)))

(defn stop
  []
  (if server
    (do
      (http/stop server)
      (alter-var-root #'server (constantly nil))
      :stopped)
    :not-running))


(comment
  (start)
  (stop)


  (m/increment-counter ::hit-rate nil)
  (m/advance-counter ::request-time {:path "/api"} 47)
  ((m/histogram ::request-size nil) 35)

  (Context/current)

  (def sb (t/create-span :request {:path "/bazz"}))

  (def span (t/start sb))

  (t/end-span span)

  )

