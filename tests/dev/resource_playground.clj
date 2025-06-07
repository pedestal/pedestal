(ns resource-playground
  "Used to compare performance of different resource exposing techniques (service-map configuration vs. routes"
  (:require [io.pedestal.http :as http]
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
  (http/create-server
    {::http/port          8890
     ::http/type          :jetty
     ::http/join?         false
     ;    ::http/file-path     "file-root"
     ; ::http/resource-path ""
     ::http/routes        (route/routes-from
                            #{["/version" :get version-handler :route-name ::version]}
                            (resources/file-routes
                              {:file-root "file-root"
                               :prefix    "/files"})
                            (resources/resource-routes
                              {:resource-root ""
                               :prefix        "/resources"}))}))

(def *service (atom nil))

(defn start
  []
  (if (some? @*service)
    :already-started
    (do
      (reset! *service (-> (create-server)
                            (http/start)))
      :started)))

(defn stop
  []
  (if-not (some? @*service)
    :not-running
    (do
      (swap! *service http/stop)
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


