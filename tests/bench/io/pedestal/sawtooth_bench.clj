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
            [criterium.core :as c]
            [io.pedestal.http.route.map-tree :as map-tree]
            [net.lewisship.bench :as bench]
            [io.pedestal.http.route.prefix-tree :as prefix-tree]
            [io.pedestal.http.route.sawtooth :as sawtooth]
            [clj-async-profiler.core :as prof]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.sawtooth-test :refer [dynamic-routing-table]]))

(def dynamic-routes (vec dynamic-routing-table))

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

(def infinite-dynamic-requests
  (repeatedly
    (fn []
      (route->request (rand-nth dynamic-routes)))))

(def dynamic-requests
  {:small  (doall (take 100 infinite-dynamic-requests))
   :medium (doall (take 1000 infinite-dynamic-requests))
   :large  (doall (take 100000 infinite-dynamic-requests))})

(def dynamic-routers
  {:prefix-tree (prefix-tree/router dynamic-routes)
   :sawtooth    (sawtooth/router dynamic-routes)})

(defn- execute-dynamic
  [batch-size router-name]
  (let [router-fn (dynamic-routers router-name)]
    (run! (fn [request]
            (router-fn request))
          (dynamic-requests batch-size))))


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

  (time (execute-dynamic :large :sawtooth))

  (bench/bench-for {:progress? true
                    :ratio? false}
                   [size [:small :medium :large]
                    router (keys dynamic-routers)]
                   (execute-dynamic size router))

  (prof/profile
    (dotimes [_ 100000] (execute-dynamic :large :sawtooth)))

  (prof/serve-ui 8080)


  (:routes (route/expand-routes
             #{{:app-name :example-app
                :scheme   :https
                :host     "example.com"}
               ["/department/:id/employees" :get [execute-dynamic]
                :route-name :org.example.app/employee-search
                :constraints {:name  #".+"
                              :order #"(asc|desc)"}]}))

  )

(defn logout [])
(defn search [])
(defn search-form [])
(defn intercepted [])
(defn trailing-slash [])

(def static-routing-table
  (route/expand-routes
    #{["/logout" :any `logout]
      ["/search" :get `search :constraints {:q #".+"}]
      ["/search" :post `search-form]
      ["/intercepted" :get `intercepted ]
      ["/trailing-slash/child-path" :get trailing-slash :route-name :admin-trailing-slash]
      ["/hierarchical/intercepted" :get intercepted :route-name :hierarchical-intercepted]
      ["/terminal/intercepted" :get intercepted :route-name :terminal-intercepted]}))

(def static-routes (-> static-routing-table :routes vec))

(def infinite-static-requests
  (repeatedly
    (fn []
      (route->request (rand-nth static-routes)))))

(def static-requests
  {:small  (doall (take 100 infinite-static-requests))
   :medium (doall (take 1000 infinite-static-requests))
   :large  (doall (take 100000 infinite-static-requests))})

(def static-routers
  {:map-tree (map-tree/router static-routes)
   :sawtooth (sawtooth/router static-routes)})

(defn- execute-static
  [batch-size router-name]
  (let [router-fn (static-routers router-name)]
    (run! (fn [request]
            (router-fn request))
          (static-requests batch-size))))

;; Optimizing sawtooth for static paths

;; 9 Oct 2024

;┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━┳━━━━━━━━━━━━━┓
;┃             Expression             ┃    Mean   ┃     Var     ┃
;┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╋━━━━━━━━━━━╋━━━━━━━━━━━━━┫
;┃  (execute-static :small :map-tree) ┃  18.05 µs ┃ ± 258.02 ns ┃ (fastest)
;┃  (execute-static :small :sawtooth) ┃  21.48 µs ┃ ± 695.38 ns ┃
;┃ (execute-static :medium :map-tree) ┃ 176.26 µs ┃   ± 1.92 µs ┃
;┃ (execute-static :medium :sawtooth) ┃ 202.23 µs ┃   ± 4.14 µs ┃
;┃  (execute-static :large :map-tree) ┃  16.99 ms ┃ ± 181.81 µs ┃
;┃  (execute-static :large :sawtooth) ┃  19.56 ms ┃ ± 395.42 µs ┃ (slowest)
;┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┻━━━━━━━━━━━┻━━━━━━━━━━━━━┛

(comment

  (c/quick-bench (sawtooth/router dynamic-routes))

  (time (execute-static :large :sawtooth))

  (bench/bench-for {:progress? true
                    :ratio?    false}
                   [size [:small :medium :large]
                    router (keys static-routers)]
                   (execute-static size router))

  )
