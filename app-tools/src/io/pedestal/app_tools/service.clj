(ns io.pedestal.app-tools.service
  (:require [io.pedestal.service.http :as bootstrap]
            [io.pedestal.service.log :as log]
            ;; the impl dependencies will go away
            ;; these next two will collapse to one
            [io.pedestal.service.interceptor :as interceptor :refer [definterceptorfn]]
            [io.pedestal.service.http :as bootstrap]
            [io.pedestal.service.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.service.http.route.definition :refer [expand-routes]]
            [io.pedestal.service.http.ring-middlewares :as middlewares]
            [io.pedestal.service.http.route :as route]
            [io.pedestal.app-tools.rendering-view.routes :as render-routes]
            [io.pedestal.app-tools.build :as build]
            [io.pedestal.app-tools.middleware :as app-tools-middleware]
            [io.pedestal.app-tools.host-page :as host-page]
            [ring.util.response :as ring-response]))


(definterceptorfn maybe-redirect-to-service [config]
  (interceptor/before
   ::maybe-redirect-to-service
   (fn [{:keys [request] :as context}]
     (let [uri (:uri request)
           aspect (first (keep (fn [[k v]] (when (= (:uri request) (:uri v)) v))
                               (:aspects config)))
           {:keys [host port]} (get-in config [:application :api-server])]
       (if (:use-api-server? aspect)
         (assoc context :response (ring-response/redirect (str "http://" host ":" port uri)))
         context)))))


;; define service routes
(defn dev-routes
  [config]
  (expand-routes [[["/_tools/render" {:get (render-routes/serve-render-menu config)}
                    ["/recording" {:get (render-routes/serve-recording-page config)}]
                    ["/recordings/:recording" {:get (render-routes/serve-recording config)
                                               :post (render-routes/save-recording config)}]]]]))

;; Consumed by chat-server.server/create-server
(defn dev-service-def
  [routes config port]
  {:env :prod
   ;; You can bring your own non-default interceptors. Make
   ;; sure you include routing and set it up right for
   ;; dev-mode. If you do, many other keys for configuring
   ;; default interceptors will be ignored.
   ::bootstrap/interceptors [bootstrap/not-found
                             bootstrap/log-request
                             servlet-interceptor/exception-debug
                             middlewares/cookies
                             (host-page/add-control-panel config)
                             app-tools-middleware/js-encoding
                             (middlewares/file-info)
                             (middlewares/params)
                             (build/builder config)
                             (maybe-redirect-to-service config)
                             (middlewares/file build/*public*)
                             (middlewares/file build/*tools-public*)
                             (route/router routes)]
   ::bootstrap/routes routes
   ;; Root for resource interceptor that is available by default.
   ;;              ::bootstrap/resource-path nil
   ;; Choose from [:jetty :tomcat].
   ::bootstrap/type :jetty
   ::bootstrap/port port
   ::bootstrap/join? false})

(defn dev-service
  "Return a new dev service."
  [port config]
  (-> (dev-routes config)
      (dev-service-def config port)
      bootstrap/create-server
      (#(assoc % :start-fn (::bootstrap/start-fn %)))
      (#(assoc % :stop-fn (::bootstrap/stop-fn %)))))

