(ns io.pedestal.sawtooth-bench
  "Used to run comparisons of Sawtooth vs. prefix-tree router performance."
  (:require [clojure.string :as string]
            [io.pedestal.http.route.router :as router]
            [net.lewisship.bench :as bench]
            [io.pedestal.http.route.prefix-tree :as prefix-tree]
            [io.pedestal.http.route.sawtooth :as sawtooth]
            [io.pedestal.http.sawtooth-test :refer [routing-table]]))

(def all-routes (vec routing-table))

(defn route->request
  [route]
  (let [{:keys [method port path host scheme]} route
        path' (->> (string/split path #"/")
                   (map (fn [s]
                          (cond
                            (string/starts-with? s ":") (-> (hash s) abs str)
                            (string/starts-with? s "*") "alpha/beta/gamma"
                            :else s)))
                   (string/join "/"))]
    (cond-> {:server-name    "default.host"
             :scheme         :http
             :request-method :get
             :server-port    8080
             :path-info      path'}
      port (assoc :server-port port)
      host (assoc :server-name host)
      scheme (assoc :scheme scheme)
      (not= method :any) (assoc :request-method method))))

(def infinite-requests
  (repeatedly
    (fn []
      (route->request (rand-nth all-routes)))))


(def requests
  {:small  (doall (take 100 infinite-requests))
   :medium (doall (take 1000 infinite-requests))
   :large  (doall (take 100000 infinite-requests))})

(def routers
  {:prefix-tree (prefix-tree/router routing-table)
   :sawtooth    (sawtooth/router routing-table)})

(defn- execute
  [batch-size router-name]
  (let [r (routers router-name)]
    (run! #(router/find-route r %) (requests batch-size))))


;; 14 Sep 2024
;
;┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━┳━━━━━━━━━━━━┓
;┃           Expression           ┃    Mean   ┃     Var    ┃
;┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╋━━━━━━━━━━━╋━━━━━━━━━━━━┫
;┃  (execute :small :prefix-tree) ┃ 102.94 µs ┃  ± 9.90 µs ┃ (fastest)
;┃     (execute :small :sawtooth) ┃ 109.25 µs ┃  ± 4.89 µs ┃
;┃ (execute :medium :prefix-tree) ┃ 943.94 µs ┃ ± 23.98 µs ┃
;┃    (execute :medium :sawtooth) ┃   1.13 ms ┃ ± 33.57 µs ┃
;┃  (execute :large :prefix-tree) ┃  94.68 ms ┃  ± 1.20 ms ┃
;┃     (execute :large :sawtooth) ┃ 113.50 ms ┃  ± 2.55 ms ┃ (slowest)
;┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┻━━━━━━━━━━━┻━━━━━━━━━━━━┛


(
  comment
  (time (execute :small :sawtooth))

  (bench/bench-for {:progress? true}
                   [size [:small :medium :large]
                    router (keys routers)]
                   (execute size router))

  )
