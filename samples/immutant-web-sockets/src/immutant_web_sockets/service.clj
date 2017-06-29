(ns immutant-web-sockets.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.log :as log]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [ring.util.response :as ring-resp]
            [clojure.core.async :as async]
            [immutant.web.async :as web-async]
            [io.pedestal.http.immutant.websockets :as ws]))

(defn about-page
  [request]
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about))))

(defn home-page
  [request]
  (ring-resp/response "Hello World!"))

(def routes
  ;; Defines "/" and "/about" routes with their associated :get handlers.
  (route/expand-routes #{["/" :get [(body-params/body-params) http/html-body home-page] :route-name ::home]
                         ["/about" :get [(body-params/body-params) http/html-body about-page] :route-name ::about]}))

(def ws-clients (atom {}))

(defn new-ws-client
  [ws-session send-ch]
  (web-async/send! send-ch "This will be a text message")
  (swap! ws-clients assoc ws-session send-ch))

;; This is just for demo purposes
(defn send-and-close! []
  (let [[ws-session send-ch] (first @ws-clients)]
    (web-async/send! send-ch "A message from the server")
    ;; And now let's close it down...
    (web-async/close send-ch)
    ;; And now clean up
    (swap! ws-clients dissoc ws-session)))

;; Also for demo purposes...
(defn send-message-to-all!
  [message]
  (doseq [[session channel] @ws-clients]
    ;; Unlike in the Jetty example, session is currently just a string
    (web-async/send! channel message)))

(def ws-paths
  {"/ws" {:on-connect (ws/start-ws-connection new-ws-client)
          ;; The Immutant API differs from Jetty here:
          ;; The functionality from one of :on-text/:on-binary
          ;; will override the other.
          ;; If you need both, use :on-message.
          ;;:on-text (fn [msg] (log/info :msg (str "A client sent - " msg)))
          ;;:on-binary (fn [payload offset length] (log/info :msg "Binary Message!" :bytes payload))
          :on-message (fn [msg]
                        (if (string? msg)
                          (log/info :msg (str "A client sent - " msg))
                          (log/info :msg "Binary Message!" :bytes msg)))
          :on-error (fn [t] (log/error :msg "WS Error happened" :exception t))
          :on-close (fn [num-code reason-text]
                      (log/info :msg "WS Closed:" :reason reason-text))}})

;; Consumed by immutant-web-sockets.server/create-server
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
              ::http/type :immutant
              ;; This injects a potential call to your endpoints in front of the
              ;; other routes.
              ;; This needs to be something that can cast to an Undertow HttpHandler.
              ;; A 1-arg function like this works.
              ;; A map defining a standard Pedestal Interceptor does not.
              ::http/container-options {:context-configurator #(ws/add-ws-endpoints % ws-paths)}
              ;; FIXME: (debug only) Remove these next 2 lines
              ::http/host "10.0.3.152"
              ::http/port 8081
              ;;::http/host "localhost"
              ;; FIXME: (debug only) Uncomment the next line
              ;;::http/port 8080
              })
