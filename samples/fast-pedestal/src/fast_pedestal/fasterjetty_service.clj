(ns fast-pedestal.fasterjetty-service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor.chain :as chain]io.pedestal.http.body-params
            [io.pedestal.http.request :as request]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp])
  (:import (java.nio ByteBuffer)
           (java.net InetSocketAddress)
           (org.eclipse.jetty.server Request
                                     Server)
           (org.eclipse.jetty.server.handler AbstractHandler)
           (javax.servlet.http HttpServletRequest
                               HttpServletResponse)))

;; Usually we'd use the request/ContainerRequest protocol,
;; but we're instead going to use direct interop to Jetty.
;; We'll need a function to make the path-info piece of a request
;; -------------------------------------------------

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

(def routes #{["/" :get (conj common-interceptors `home-page)]
              ["/about" :get (conj common-interceptors `about-page)]})

;(def routes `{"/" {:interceptors [(body-params/body-params) http/html-body]
;                   :get home-page
;                   "/about" {:get about-page}}})

;(def routes
;  `[[["/" {:get home-page}
;      ^:interceptors [(body-params/body-params) http/html-body]
;      ["/about" {:get about-page}]]]])

(defn direct-jetty-provider
  "Given a service-map,
  provide all necessary functionality to execute the interceptor chain,
  including extracting and packaging the base :request into context.
  These functions/objects are added back into the service map for use within
  the server-fn.
  See io.pedestal.http.impl.servlet-interceptor.clj as an example.

  Interceptor chains:
   * Terminate based on the list of :terminators in the context.
   * Call the list of functions in :enter-async when going async.  Use these to toggle async mode on the container
   * Will use the fn at :async? to determine if the chain has been operating in async mode (so the container can handle on the outbound)"
  [service-map]
  (let [interceptors (::http/interceptors service-map)]
    (assoc service-map ::handler (proxy [AbstractHandler] []
                                   (handle [target ^Request base-request servlet-req servlet-resp]
                                     ;; This is where you'd build a context and execute interceptors
                                     (let [resp (.getResponse base-request)
                                           initial-context {:request {:query-string     (.getQueryString base-request)
                                                                      :request-method   (keyword (.toLowerCase (.getMethod base-request)))
                                                                      :body             (.getInputStream base-request)
                                                                      :path-info        (.getRequestURI base-request)
                                                                      :async-supported? (.isAsyncSupported base-request)}}
                                           resp-ctx (chain/execute initial-context
                                                                       interceptors)]
                                       (.setContentType resp (get-in resp-ctx [:response :headers "Content-Type"]))
                                       (.setStatus resp (get-in resp-ctx [:response :status]))
                                       (.sendContent (.getHttpOutput resp)
                                                     (ByteBuffer/wrap (.getBytes ^String (get-in resp-ctx [:response :body]) "UTF-8")))
                                       (.setHandled base-request true)))))))

(defn jetty-server-fn
  "Given a service map (with interceptor provider established) and a server-opts map,
  Return a map of :server, :start-fn, and :stop-fn.
  Both functions are 0-arity"
  [service-map server-opts]
  (let [handler (::handler service-map)
        {:keys [host port join?]
         :or {host "127.0.0.1"
              port 8080
              join? false}} server-opts
        addr (InetSocketAddress. ^String host ^int port)
        server (doto (Server. addr)
                 (.setHandler handler))]
    {:server server
     :start-fn (fn []
                 (.start server)
                 (when join? (.join server))
                 server)
     :stop-fn (fn []
                (.stop server)
                server)}))

;; Consumed by peddemo.server/create-server
;; See http/default-interceptors for additional options you can configure
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
   ;; Call directly into Jetty, skipping Pedestal's Jetty Provider/options
   ::http/type jetty-server-fn
   ::http/chain-provider direct-jetty-provider

   ::http/port (or (some-> (System/getenv "PORT")
                           Integer/parseInt)
                   8080)

   ;; Our provider doesn't recognize these
   ;::http/container-options {:h2c? true
   ;                          :h2? false
   ;                          ;:keystore "test/hp/keystore.jks"
   ;                          ;:key-password "password"
   ;                          ;:ssl-port 8443
   ;                          :ssl? false
   ;                          ;:context-configurator #(jetty-util/add-servlet-filter % {:filter DoSFilter})
   ;                          }
   })

(defn -main [& args]
  (println "Starting your server...")
  (-> service
      (assoc ::http/join? true)
      http/create-server
      http/start))

(defn dev []
  (-> service
      http/create-server
      http/start))
