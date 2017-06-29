(ns jetty-web-sockets.service
  (:require [clojure.core.async :as async]
            [clojure.pprint :refer (pprint)]
            [io.pedestal.http :as http]
            [io.pedestal.log :as log]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.immutant.websockets :as ped-ws]
            [io.pedestal.http.ring-middlewares :as m-w]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [ring.middleware.session.cookie :as cookie]
            [ring.util.response :as ring-resp]
            [immutant.web.async :as web-async])
  #_(:import [org.eclipse.jetty.websocket.api Session]))

(def ws-clients (atom {}))

(defn about-page
  [request]
  (log/debug :route "About")
  (comment   {:status 200 :body "About"})
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about))))

(def build-websocket
  {:name ::ws-builder
   :enter (fn [{:keys [:request]
                :as ctx}]
            (log/info :route "/ws")
            (try
              (let [{{:keys [:websocket?]
                      :as request} :request} ctx]
                (comment (log/info :upgrading? websocket?
                                   :request request))
                (log/info :keys (keys ctx))
                (log/info :context ctx)
                (if (or true websocket?)
                  ;; websocket? isn't getting set
                  (let [session (:session request)
                        callbacks {:on-open #(println "WS opened:" %)
                                   :on-close (fn [channel {:keys [code reason]}]
                                               (println (str "WS: " channel " closing. " code ", " reason)))
                                   :on-error (fn [channel throwable]
                                               (println (str "WS-error: "channel ": " throwable)))
                                   :on-message (fn [channel message]
                                                 (println (str "WS: " channel " -- " message)))}
                        ws (web-async/as-channel request callbacks)]
                    (println "Trying to associate a new websocket with:"
                             (keys ctx)
                             "\nFor Session:" session)
                    (pprint ctx)
                    (swap! ws-clients assoc (:id session) ws)
                    ;; This fails because
                    ;; "Intercepter Exception: No implementation of method: :default-content-type of protacol:
                    ;; #'io.pedestal.http.impl.servlet-interceptor/WriteableBody found for class:
                    ;; org.projectodd.wunderboss.web.async.ServletHttpChannel
                    (assoc ctx :response ws))
                  ctx))
              (catch Exception ex
                (log/error :exception ex))))})

(defn home-page
  [{:keys [session]
    :as request}]
  (log/info :keys (keys request))
  (log/info :session session)
  (update-in (ring-resp/response "Hello World!")
             [:session] assoc :id "rondam-id"))

(def cookie-session (m-w/session {:store (cookie/cookie-store {:key "a 16-byte secret"})}))

(def routes
  ;; Defines "/" and "/about" routes with their associated :get handlers.
  ;; The interceptors defined after the verb map (e.g., {:get home-page}
  ;; apply to / and its children (/about).
  (route/expand-routes #{["/"      :get [cookie-session home-page] :route-name ::home]
                         ["/about" :get about-page :route-name ::about]
                         #_["/ws"    :get [cookie-session
                                         build-websocket]]}))

(defn new-ws-client
  [ws-session send-ch]
  (log/info :sender send-ch
            :class (class send-ch))
  (comment
    (async/put! send-ch "This will be a text message"))
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
    ;; The Pedestal Websocket API performs all defensive checks before sending,
    ;;  like `.isOpen`, but this example shows you can make calls directly on
    ;;  on the Session object if you need to
    (web-async/send! channel message)))

(comment
  (defn ws-servlet
    "Given a function
  (that takes a ServletUpgradeRequest and ServletUpgradeResponse and returns a WebSocketListener),
  return a WebSocketServlet that uses the function to create new WebSockets (via a factory)."
    [creation-fn]
    (comment
      ;; Gyahh. Icky Java over-architecture silliness.
      ;; Surely I don't need all this.
      (let [creator (reify WebSocketCreator
                      (createWebSocket [this req response]
                        (creation-fn req response)))
            (proxy [WebSocketServlet] []
              (configure [factory]
                (.setCreator factory creator)))])))

  (defn add-ws-endpoints
    [ctx ws-paths]
    ;; This is what the jetty version actually does.
    ;; Q: Do I really need to do something this involved?
    (let [listener-fn (fn [req response ws-map]
                        (make-ws-listener ws-map))]
      (doseq [[path ws-map] ws-paths]
        (let [servlet (ws-servlet (fn [req response]
                                    (listener-fn req response ws-map)))]
          (.addServlet ctx (ServletHolder. servlet) path)))
      ctx)))

(def ws-paths
  {"/ws" {:on-connect (ped-ws/start-ws-connection new-ws-client)
          ;; Have to pick one or the other, or the last one in wins
          ;; Use on-message instead, if you need both
          :on-text (fn [msg] (log/info :msg (str "A client sent - " msg)))
          ;; :on-binary (fn [payload offset length] (log/info :msg "Binary Message!" :bytes payload))
          :on-error (fn [t] (log/error :msg "WS Error happened" :exception t))
          :on-close (fn [num-code reason-text]
                      (log/info :msg "WS Closed:" :reason reason-text))}})

;; Consumed by jetty-web-sockets.server/create-server
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
              ::http/container-options {:context-configurator #(ped-ws/add-ws-endpoints % ws-paths)}
              ::http/host "10.0.3.152"
              ::http/port 8080})
