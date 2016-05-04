(ns fast-pedestal.fastjetty-service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]
            [io.pedestal.http.jetty.util :as jetty-util]))

(defn about-page
  [request]
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about-page))))

(defn home-page
  [request]
  (ring-resp/response "Hello World!"))

;; Defines "/" and "/about" routes with their associated :get handlers.
;; The interceptors defined after the verb map (e.g., {:get home-page}
;; apply to / and its children (/about).
(def common-interceptors [(body-params/body-params) http/html-body])

;; Tabular routes
(def routes #{["/" :get (conj common-interceptors `home-page)]
              ["/about" :get (conj common-interceptors `about-page)]})

;; Map-based routes
;(def routes `{"/" {:interceptors [(body-params/body-params) bootstrap/html-body]
;                   :get home-page
;                   "/about" {:get about-page}}})

;; Terse/Vector-based routes
;(def routes
;  `[[["/" {:get home-page}
;      ^:interceptors [(body-params/body-params) bootstrap/html-body]
;      ["/about" {:get about-page}]]]])



(def service
  {:env :prod
   ::http/interceptors [;http/log-request
                        ;http/not-found
                        route/query-params
                        ;(middleware/file-info)
                        ;; We don't have any resources
                        ;;  -- we could also use a ServletFilter to avoid calling
                        ;;     into the application/service
                        ;(middleware/resource "public")
                        ;(route/method-param :_method)
                        ;; The routes are static and compiled at startup.
                        ;; We'll be using the MapTree router...
                        (route/router (route/expand-routes routes))]
   ::http/join? false
   ::http/type :jetty
   ::http/port (or (some-> (System/getenv "PORT")
                           Integer/parseInt)
                   8080)
   ::http/container-options {:h2c? true
                             :h2? false
                             ;:keystore "test/hp/keystore.jks"
                             ;:key-password "password"
                             ;:ssl-port 8443
                             :ssl? false
                             ;:context-configurator #(jetty-util/add-servlet-filter % {:filter DoSFilter})
                             }})

(defn -main [& args]
  (println "Starting your server...")
  (-> service
      (assoc ::http/join? true)
      http/create-server
      http/start))

