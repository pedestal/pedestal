(ns helloworld-metrics.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [io.pedestal.log :as log]
            [ring.util.response :as ring-resp])
  (:import [com.codahale.metrics MetricRegistry]
           [com.readytalk.metrics StatsDReporter]
           [java.util.concurrent TimeUnit]))


(defn statsd-reporter [^MetricRegistry registry]
  "Builds statsd reporter"
  (doto (some-> (StatsDReporter/forRegistry registry)
                (.build "localhost" 8125))
    (.start 3, TimeUnit/SECONDS)))

;; Defines statsd recorder to be passed to metrics functions
(def custom-recorder (log/metric-registry statsd-reporter))

;; Metric names
;; --------------
;; All metric names are converted into Strings when they're processed.
;; In code, it's common to use namespaced-keywords, to ensure your metrics
;; are appropriately namespaced.


(defn statsd-page
  "A sample page to trigger statsd to count up"
  [request]
  (log/counter custom-recorder ::statsd-hits 1)
  (ring-resp/response
    "Statsd metrics should start.
    Type <code>nc -kul 8125</code>. Then reload this page.</body>\n"))

(defn gauge-fn
  "A function used in a gauge metric. This function returns a value."
  []
  (rand-int Integer/MAX_VALUE))

(defn home-page
  "A sample page to trigger four metrics to be updated"
  [request]
  (log/counter ::homepage-hits 1)
  (log/gauge ::random-home-guage gauge-fn)
  (log/histogram ::distribution-of-rand (rand-int Integer/MAX_VALUE))
  (log/meter ::homepage-reqs-rate (rand-int Integer/MAX_VALUE))
  (ring-resp/response "Hello World!"))

(defroutes routes
  ;; Defines "/" and "/about" routes with their associated :get handlers.
  ;; The interceptors defined after the verb map (e.g., {:get home-page}
  ;; apply to / and its children (/about).
  [[["/" {:get home-page}
     ^:interceptors [(body-params/body-params) http/html-body]
     ["/statsd" {:get statsd-page}]]]])

;; Consumed by my-sample.server/create-server
;; See http/default-interceptors for additional options you can configure
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; ::http/interceptors []
              ::http/routes routes

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::http/allowed-origins ["scheme://host:port"]

              ;; Root for resource interceptor that is available by default.
              ::http/resource-path "/public"

              ;; Either :jetty, :immutant or :tomcat (see comments in project.clj)
              ::http/type :jetty
              ;;::http/host "localhost"
              ::http/port 8080})
