(ns org.example.sse.routes
  (:require [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.sse :as sse]
            [clojure.core.async :refer [go-loop <! >! close! timeout]]))

(defn hello-handler
  [_request]
  {:status 200
   :body   "Hello"})

(defn greet-handler
  [request]
  (let [{:keys [name]} (:json-params request)]
    {:status 200
     :body   (str "Hello, " name ".")}))

(defn countdown-callback
  [count]
  (fn [event-ch _context]
    (go-loop [i count]
      (if (zero? i)
        (do
          (>! event-ch "Blastoff!")
          (close! event-ch))
        (do
          (>! event-ch (format "%d ..." i))
          (<! (timeout 1000))
          (recur (dec i)))))))

(def start-countdown
  (interceptor
    {:name  ::start-countdown
     :enter (fn [context]
              (let [count (parse-long (get-in context [:request :query-params :count]))]
                (sse/start-stream (countdown-callback count) context)))}))

(defn routes
  []
  (table/table-routes
    [["/hello" :get [hello-handler] :route-name ::hello]
     ["/hello" :post [greet-handler] :route-name ::greet]
     ["/start" :get [start-countdown]]]))
