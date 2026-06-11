;; tag::ns[]
(ns websocket-demo
  (:require [io.pedestal.connector :as conn]
            [io.pedestal.http.http-kit :as hk]
            [io.pedestal.service.websocket :as ws]))
;; end::ns[]

;; tag::home-page[]
(defn home-page
  [_request]
  {:status 200 :body "Hello, World!"})
;; end::home-page[]

;; tag::ws-interceptor[]
(def echo-interceptor
  (ws/websocket-interceptor
    ::echo
    {:on-open  (fn [_channel _request]                        ;; <1>
                 (println "WebSocket connection opened")
                 nil)
     :on-text  (fn [channel _proc text]                       ;; <2>
                 (ws/send-text! channel (str "echo: " text)))
     :on-close (fn [_channel _proc reason]                    ;; <3>
                 (println "WebSocket connection closed:" reason))}))
;; end::ws-interceptor[]

;; tag::routes[]
(def routes
  #{["/"         :get home-page    :route-name ::home]
    ["/ws/echo"  :get echo-interceptor]})                     ;; <1>
;; end::routes[]

;; tag::connector[]
(defn create-connector []
  (-> (conn/default-connector-map 8890)
      (conn/with-default-interceptors)
      (conn/with-routes routes)
      (hk/create-connector nil)))

(defonce *connector (atom nil))

(defn start []
  (reset! *connector (conn/start! (create-connector))))

(defn stop []
  (conn/stop! @*connector)
  (reset! *connector nil))

(defn restart []
  (when @*connector (stop))
  (start))
;; end::connector[]
