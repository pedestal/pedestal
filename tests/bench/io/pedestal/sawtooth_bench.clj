; Copyright 2024 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.sawtooth-bench
  "Used to run comparisons of Sawtooth vs. prefix-tree router performance."
  (:require [clojure.string :as string]
            [net.lewisship.bench :as bench]
            [io.pedestal.http.route.prefix-tree :as prefix-tree]
            [io.pedestal.http.route.sawtooth :as sawtooth]
            [clj-async-profiler.core :as prof]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.sawtooth-test :refer [routing-table]]))

(def all-routes (vec routing-table))

(defn route->request
  [route]
  (let [{:keys [method port path host scheme]} route
        path' (if (= path "/")
                path
                (->> (string/split path #"/")
                     (map (fn [s]
                            (cond
                              (string/starts-with? s ":") (-> (hash s) abs str)
                              (string/starts-with? s "*") "alpha/beta/gamma"
                              :else s)))
                     (string/join "/")))]
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
  {:prefix-tree (prefix-tree/router all-routes)
   :sawtooth    (sawtooth/router all-routes)})

(defn- execute
  [batch-size router-name]
  (let [router-fn (routers router-name)]
    (run! (fn [request]
            (router-fn request))
          (requests batch-size))))


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

;; Using (execute :large :sawtooth), time/req for sawtooth
;; is 1.13 µs.

;; 16 Sep 2024

;┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━┳━━━━━━━━━━━━┓
;┃           Expression           ┃    Mean   ┃     Var    ┃
;┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╋━━━━━━━━━━━╋━━━━━━━━━━━━┫
;┃  (execute :small :prefix-tree) ┃  99.07 µs ┃  ± 5.12 µs ┃ (fastest)
;┃     (execute :small :sawtooth) ┃ 104.05 µs ┃  ± 5.02 µs ┃
;┃ (execute :medium :prefix-tree) ┃   1.02 ms ┃ ± 56.25 µs ┃
;┃    (execute :medium :sawtooth) ┃   1.06 ms ┃ ± 53.26 µs ┃
;┃  (execute :large :prefix-tree) ┃  96.05 ms ┃  ± 3.90 ms ┃
;┃     (execute :large :sawtooth) ┃ 106.28 ms ┃  ± 5.26 ms ┃ (slowest)
;┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┻━━━━━━━━━━━┻━━━━━━━━━━━━┛

;; Ah, benchmarking. prefix-tree implementation didn't change,
;; yet it's faster today.  For the :large tree, the delta
;; has shifted from 18.82ms to 10.23ms.  Remember, that's
;; the time to route 100000 requests, so the time/request
;; for sawtooth is now 1.06 µs.

;; 16 Sep 2024 - #2

;┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━┳━━━━━━━━━━━━━┓
;┃           Expression           ┃    Mean   ┃     Var     ┃
;┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╋━━━━━━━━━━━╋━━━━━━━━━━━━━┫
;┃  (execute :small :prefix-tree) ┃  91.16 µs ┃   ± 2.04 µs ┃ (fastest)
;┃     (execute :small :sawtooth) ┃  91.58 µs ┃   ± 1.65 µs ┃
;┃ (execute :medium :prefix-tree) ┃ 947.39 µs ┃  ± 19.93 µs ┃
;┃    (execute :medium :sawtooth) ┃ 971.17 µs ┃  ± 19.25 µs ┃
;┃  (execute :large :prefix-tree) ┃  92.41 ms ┃   ± 1.70 ms ┃
;┃     (execute :large :sawtooth) ┃  95.13 ms ┃ ± 494.08 µs ┃ (slowest)
;┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┻━━━━━━━━━━━┻━━━━━━━━━━━━━┛

;; Each benchmark is running with different random data, so the numbers
;; do keep shifting.  The latest main optimization is identifying
;; sub-selections where there are no more parameters and, much like
;; the map-tree router, using a map to look up the route from the remaining path.

(comment

  (time (execute :large :sawtooth))

  (bench/bench-for {:progress? true
                    :ratio? false}
                   [size [:small :medium :large]
                    router (keys routers)]
                   (execute size router))

  (prof/profile
    (dotimes [_ 100000] (execute :large :sawtooth)))

  (prof/serve-ui 8080)


  (:routes (route/expand-routes
             #{{:app-name :example-app
                :scheme   :https
                :host     "example.com"}
               ["/department/:id/employees" :get [execute]
                :route-name :org.example.app/employee-search
                :constraints {:name  #".+"
                              :order #"(asc|desc)"}]}))

  )
