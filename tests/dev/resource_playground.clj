(ns resource-playground
  "Used to compare performance of different resource exposing techniques (service-map configuration vs. routes"
  (:require [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.service.resources :as resources]
            [io.pedestal.http.route.prefix-tree :as pf]
            [criterium.core :as c]
            [io.pedestal.http.route.sawtooth :as saw]
            [io.pedestal.http.route :as route]))

(defn version-handler
  [_]
  {:status 200
   :body   "1.0.0"})

(defn create-server
  []
  ;; Was io.pedestal.http/create-server with a service map (port 8890, Jetty, the default interceptors);
  ;; the commented-out ::http/file-path and ::http/resource-path options would now be
  ;; (conn/with-interceptor (ring-middlewares/file "file-root")) or (ring-middlewares/resource "").
  (-> (conn/default-connector-map 8890)
      (conn/with-default-interceptors)
      (conn/with-routes
        #{["/version" :get version-handler :route-name ::version]}
        (resources/file-routes
          {:file-root "file-root"
           :prefix    "/files"})
        (resources/resource-routes
          {:resource-root ""
           :prefix        "/resources"}))
      (jetty/create-connector nil)))

(def *service (atom nil))

(defn start
  []
  (if (some? @*service)
    :already-started
    (do
      (reset! *service (-> (create-server)
                            (conn/start!)))
      :started)))

(defn stop
  []
  (if-not (some? @*service)
    :not-running
    (do
      (swap! *service conn/stop!)
      (reset! *service nil)
      :stopped)))

(comment
  (start)
  (stop)

  )

;; Rough test using Apache Bench, on single OS X M1 Mac
;; ab -n 1000 -c 4
;;
;; Mean time/request (ms)
;;
;; Test                          Interceptor                       Routes
;; ----------------------------------------------------------------------
;; small file                    1.8                               1.07
;; large file                    1.09                               .6
;; small resource                 .8                                .69
;; large resource                1.05                               .38
;; file: index of dir             .89                               .47
;;
;; Route column was collected with interceptors NOT present
;; (times were worse with both interceptors and routes enabled).
;; Ran each ab command several times in a row, until the numbers
;; looked reasonably stable.

(comment                                                    ; do
  (def routes (route/expand-routes #{["/api/release-cycle/:version/cancel" :get identity :route-name :cancel]
                                     ["/api/release-cycle/resume-current" :get identity :route-name :resume]}))

  (def pf (pf/router routes))
  (def saw (saw/router routes))


  (pf {:request-method :get
           :path-info "/api/release-cycle/resume-current"}) ; => nil

  (println "**** PREFIX-TREE")

  (c/quick-bench
    (pf {:request-method :get
                      :path-info      "/api/release-cycle/1.2.3/cancel"})) ;=> :cancel route

  (println "**** SAWTOOTH")

  (saw {:request-method :get
       :path-info "/api/release-cycle/resume-current"})     ; => :resume route

  (c/quick-bench
    (saw {:request-method :get
          :path-info      "/api/release-cycle/1.2.3/cancel"})) ; => :cancel route

  )


