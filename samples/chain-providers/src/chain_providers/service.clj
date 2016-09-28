(ns chain-providers.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp])
  (:import (java.nio ByteBuffer)
           (java.net InetSocketAddress)
           (org.eclipse.jetty.server Request
                                     Server)
           (org.eclipse.jetty.server.handler AbstractHandler)
           (javax.servlet.http HttpServletRequest
                               HttpServletResponse)))

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

(defn my-custom-provider
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
  (assoc service-map ::handler (proxy [AbstractHandler] []
                                 (handle [target base-request servlet-req servlet-resp]
                                   ;; This is where you'd build a context and execute interceptors
                                   (let [resp (.getResponse base-request)]
                                     (.setContentType resp "text/html; charset=utf-8")
                                     (.setStatus resp 200)
                                     (.sendContent (.getHttpOutput resp)
                                                   (ByteBuffer/wrap (.getBytes "Hello World" "UTF-8")))
                                     (.setHandled base-request true))))))

(defn my-custom-server-fn
  "Given a service map (with interceptor provider established) and a server-opts map,
  Return a map of :server, :start-fn, and :stop-fn.
  Both functions are 0-arity"
  [service-map server-opts]
  (let [handler (::handler service-map)
        {:keys [host port join?]
         :or {host "127.0.0.1"
              port 8080
              join? false}} server-opts
        addr (InetSocketAddress. host port)
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
              ::http/type my-custom-server-fn
              ::http/chain-provider my-custom-provider
              ;;::http/host "localhost"
              ::http/port 8080
              ;; Container options are being ignored by the server implementation above
              ;::http/container-options {:h2c? true
              ;                          :h2? false
              ;                          ;:keystore "test/hp/keystore.jks"
              ;                          ;:key-password "password"
              ;                          ;:ssl-port 8443
              ;                          :ssl? false}
              })
