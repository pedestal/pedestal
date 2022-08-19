; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.route.route-bench
  "Compare performance of old and new routing algorithms."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [incanter.stats :as stats]
            [incanter.core :as incanter]
            [incanter.charts :as charts]
            [io.pedestal.http.route.router :as router]
            [io.pedestal.http.route.path :as path]
            [io.pedestal.http.route.linear-search :as linear]
            [io.pedestal.http.route.prefix-tree :as prefix-tree]
            [io.pedestal.route.gen :as gen])
  (:import java.text.DecimalFormat))


(defn time*
  ([thunk]
     (time* 1 thunk identity))
  ([n thunk]
     (time* n thunk identity))
  ([n thunk f]
     (map (fn [_] (let [start (. System (nanoTime))
                        ret (thunk)
                        stop (. System (nanoTime))]
                    {:time (- stop start)
                     :result (f ret)}))
          (range 0 n))))

(def dformat (DecimalFormat. "####.##"))

(defn bd [n]
  (BigDecimal. (.format dformat (/ n 1000000))))

(defn stats [requests times]
  (let [ts (map :time times)
        mean (stats/mean ts)
        num-requests (count requests)
        errors (mapcat :result times)]
    {:n (count times)
     :sd (bd (stats/sd ts))
     :mean (bd mean)
     :max (bd (apply max ts))
     :min (bd (apply min ts))
     :nerr (count errors)
     :errors errors
     :nreq num-requests
     :ns/req (int (/ mean num-requests))
     :req/sec (int (/ num-requests (/ mean 1000000000)))}))

(defn route-all [router requests]
  (doall (keep #(let [route (router/find-route router %)]
                  (when-not (= (:path-params route)
                               (::gen/generated-params %))
                    {:route route
                     :request %}))
               requests)))

(defn test-routers [routes routers n]
  (let [requests (doall (mapcat gen/requests-for-route routes))]
    (mapv (fn [{:keys [router-name ctor]}]
            (let [router (ctor routes)
                  times (time* n #(route-all router (shuffle requests)))]
              (-> (stats requests times)
                  (assoc :name router-name
                         :nroute (count routes)))))
          routers)))

(defn print-results [results-map]
  (pp/print-table [:name :sd :mean :nerr :nroute :nreq :ns/req :req/sec]
                  results-map))

(defn expand-route-path [route]
  (->> (:path route)
       path/parse-path
       (merge route)
       path/merge-path-regex))

(def routers [{:router-name "linear" :ctor #(linear/router (map expand-route-path %))}
              {:router-name "prefix-tree" :ctor prefix-tree/router}])

(defn make-chart [data file]
  (let [d (incanter/to-dataset (doall data))]
    (incanter/with-data d
      (doto (charts/line-chart :nroute :ns/req
                               :group-by :name
                               :legend true
                               :title "Route Algorithm Comparison"
                               :x-label "Number of routes"
                               :y-label "Nanoseconds per request")
        (.setBackgroundPaint (new java.awt.Color 248 248 248))
        (incanter/save file :width 1000)))))

(defn run-bench [{:keys [min-routes max-routes step sample-size silent]
                  :or {min-routes 10
                       max-routes 100
                       step 10
                       sample-size 100
                       silent false}}]
  (let [results (atom [])]
    (try
      (doseq [n (range min-routes (+ max-routes step) step)]
        (let [routes (gen/generate-routes n)]
          (let [r (test-routers routes routers sample-size)]
            (swap! results conj r)
            (when silent
              (println (int (/ (- max-routes n) step))))
            (when-not silent
              (print-results (sort-by :ns/req r))))))
      (when-not silent
        (let [file (io/file "bench/io/pedestal/route/charts/nanoseconds-per-request.png")]
          (make-chart (flatten @results) file)
          (println (format "Finished! See results in:\n%s"
                           (.getAbsolutePath file)))))
      (catch Throwable e
        (let [{:keys [req routes router-name]} (ex-data e)]
          (println (format "could not route request with router '%s':"
                           router-name))
          (pp/pprint req)
          (println "to tree built from these routes")
          (doseq [x (sort (map :path routes))]
            (println x)))))))

(defn -main
  "Run a benchmark comparing the old and new routing algorithms. We do
  a full warm-up here so that the first result will be accurate."
  []
  (let [max-routes 200]
    (println "Warming up...")
    (run-bench {:max-routes max-routes
                :silent true})
    (println "Starting benchmark...")
    (run-bench {:max-routes max-routes})))
