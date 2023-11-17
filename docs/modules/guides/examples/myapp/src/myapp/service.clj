(ns myapp.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]))

(defn about-page
  [request]
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about-page))))

(defn home-page
  [request]
  (ring-resp/response "Hello World!"))

(def echo
  {:name :echo
   :enter
   (fn [context]
     (let [request  (:request context)
           response (ok context)]
       (assoc context :response response)))})

;; Defines "/" and "/about" routes with their associated :get handlers.
;; The interceptors defined after the verb map (e.g., {:get home-page}
;; apply to / and its children (/about).
(def common-interceptors [(body-params/body-params) http/html-body])

;; tag::original-routes[]
(def routes #{["/" :get (conj common-interceptors `home-page)]
              ["/about" :get (conj common-interceptors `about-page)]})
;; end::original-routes[]


;; tag::yfa-routes[]
(def routes
   #{["/todo"                 :post   [db-interceptor list-create]]
     ["/todo"                 :get    echo :route-name :list-query-form]
     ["/todo/:list-id"        :get    [entity-render db-interceptor list-view]]
     ["/todo/:list-id"        :post   [entity-render list-item-view db-interceptor list-item-create]]
     ["/todo/:list-id/:item"  :get    [entity-render list-item-view]]
     ["/todo/:list-id/:item"  :put    echo :route-name :list-item-update]
     ["/todo/:list-id/:item"  :delete echo :route-name :list-item-delete]})
;; end::yfa-routes[]

;; tag::yfa-routes-constrained[]
(def numeric #"[0-9]+")
(def url-rules {:list-id numeric :item numeric})

(def routes
   #{["/todo"                 :post   [db-interceptor list-create]]
     ["/todo"                 :get    echo :route-name :list-query-form]
     ["/todo/:list-id"        :get    [entity-render db-interceptor list-view]                       :constraints url-rules]
     ["/todo/:list-id"        :post   [entity-render list-item-view db-interceptor list-item-create] :constraints url-rules]
     ["/todo/:list-id/:item"  :get    [entity-render list-item-view]                                 :constraints url-rules]
     ["/todo/:list-id/:item"  :put    echo :route-name :list-item-update                             :constraints url-rules]
     ["/todo/:list-id/:item"  :delete echo :route-name :list-item-delete :constraints url-rules]})
;; end::yfa-routes-constrained[]

;; tag::with-wildcard[]
(def routes
   #{["/org/*todos" :get   [parse-hierarchy lookup-list]]})
;; end::with-wildcard[]


;; Consumed by myapp.server/create-server
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
              ::http/port 8080
              ;; Options to pass to the container (Jetty)
              ::http/container-options {:h2c? true
                                        :h2? false
                                        ;:keystore "test/hp/keystore.jks"
                                        ;:key-password "password"
                                        ;:ssl-port 8443
                                        :ssl? false}})
