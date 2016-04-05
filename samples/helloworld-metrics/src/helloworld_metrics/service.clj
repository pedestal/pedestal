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

(defn todays-date
  "Gets today's date for metrics' name"
  []
  (.format (java.text.SimpleDateFormat. "MM/dd/yyyy") (java.util.Date.)))

(defn statsd-reporter [^MetricRegistry registry]
  "Builds statsd reporter"
  (doto (some-> (StatsDReporter/forRegistry registry)
                (.build "localhost" 8125))
    (.start 3, TimeUnit/SECONDS)))

;; Defines statsd reporter to be passed to metrics functions
(def reporter (log/metric-registry statsd-reporter))

(defn statsd-page
  "A sample page to trigger statsd to count up"
  [request]
  (let [counter-name (str "helloworld counter " (todays-date))]
    (log/counter reporter counter-name 1)
    (-> (ring-resp/response
         (format "<body>Statsd metrics should start.<img src=\"smile.png\"/><br/>
                  Type <code>nc -kul 8125</code>. Then reload this page.</body>\n"))
        (ring-resp/content-type "text/html"))))

(defn gauge-fn
  "A function used in a gauge metric. This function returns a value."
  []
  (rand-int Integer/MAX_VALUE))

(defn home-page
  "A sample page to trigger four metrics to be updated"
  [request]
  (let [counter-name (str "helloworld counter "  (todays-date))
        gauge-name (str "helloworld gauge " (todays-date))
        hist-name (str "helloworld histgram " (todays-date))
        meter-name (str "helloworld meter " (todays-date))]
    ;; Counter counts how many times this service has been requested.
    (log/counter counter-name 1)
    ;; Gauge returns some random integer processed by gauge-fn.
    (log/gauge gauge-name gauge-fn)
    ;; Histogram calcuates value distributions (min, max, mean, etc.)  on the backend.
    (log/histogram hist-name (rand-int Integer/MAX_VALUE))
    ;; Meter takes long value as an occurence of some event; in this
    ;; case, random integer as number of events.
    (log/meter meter-name (rand-int Integer/MAX_VALUE))
    ;; Http response includes an image file which means a broswer
    ;; makes at least two requests to Pedestal but once to this
    ;; service. The counter above counts request to this service.
    (-> (ring-resp/response (format "<body>Hello World! <img src=\"smile.png\"/></body>\n"))
        (ring-resp/content-type "text/html"))))

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
              ;; You can configure your specific metric system if needed.
              ;;  - by default, metrics are published to JMX
              ;; ::http/metrics-init #(log/metric-registry log/jmx-reporter
              ;;                                           log/log-reporter)

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
