;; tag::original-service[]
(ns server-sent-events.service
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

;; Defines "/" and "/about" routes with their associated :get handlers.
;; The interceptors defined after the verb map (e.g., {:get home-page}
;; apply to / and its children (/about).
(def common-interceptors [(body-params/body-params) http/html-body])

;; Tabular routes
(def routes #{["/" :get (conj common-interceptors `home-page)]
              ["/about" :get (conj common-interceptors `about-page)]})

;; Map-based routes
;(def routes `{"/" {:interceptors [(body-params/body-params) http/html-body]
;                   :get home-page
;                   "/about" {:get about-page}}})

;; Terse/Vector-based routes
;(def routes
;  `[[["/" {:get home-page}
;      ^:interceptors [(body-params/body-params) http/html-body]
;      ["/about" {:get about-page}]]]])


;; Consumed by server-sent-events.server/create-server
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

              ;; Tune the Secure Headers
              ;; and specifically the Content Security Policy appropriate to your service/application
              ;; For more information, see: https://content-security-policy.com/
              ;;   See also: https://github.com/pedestal/pedestal/issues/499
              ;;::http/secure-headers {:content-security-policy-settings {:object-src "'none'"
              ;;                                                          :script-src "'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:"
              ;;                                                          :frame-ancestors "'none'"}}

              ;; Root for resource interceptor that is available by default.
              ::http/resource-path "/public"

              ;; Either :jetty, :immutant or :tomcat (see comments in project.clj)
              ;;  This can also be your own chain provider/server-fn -- http://pedestal.io/reference/architecture-overview#_chain_provider
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
;; end::original-service[]

;; tag::cleaned-up[]
(ns server-sent-events.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]))

(defn home-page
  [request]
  (ring-resp/response "Hello World!"))

(def routes
  `[[["/" {:get home-page}]]])

(def service {:env :prod
              ::http/routes routes
              ::http/type :jetty
              ::http/port 8080})
;; end::cleaned-up[]

;; tag::require-sse[]
(ns server-sent-events.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
    ;; require SSE
            [io.pedestal.http.sse :as sse]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]
    ;; require core.async
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as chan]))
;; end::require-sse[]

(defn home-page
  [request]
  (ring-resp/response "Hello World!"))

;; tag::html-home[]
(ns server-sent-events.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
    ;; require SSE
            [io.pedestal.http.sse :as sse]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]
    ;; require core.async
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as chan]
            [hiccup.core :as hiccup]))

(def js-string
  "
var eventSource = new EventSource(\"http://localhost:8080/counter\");
eventSource.addEventListener(\"counter\", function(e) {
  console.log(e);
  var counterEl = document.getElementById(\"counter\");
  counter.innerHTML = e.data;
});
")

(defn home-page
  [request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (hiccup/html [:html
                       [:head
                        [:script {:type "text/javascript"}
                         js-string]]
                       [:body
                        [:div
                         [:span "Counter: "]
                         [:span#counter]]]])})

(defn stream-ready [event-chan context]
  (dotimes [i 10]
    (when-not (chan/closed? event-chan)
      (async/>!! event-chan {:name "counter" :data i})
      (Thread/sleep 1000)))
  (async/close! event-chan))

(def routes
  `[[["/" {:get home-page}
      ["/counter" {:get [::send-counter (sse/start-event-stream stream-ready)]}]]]])

(def service {:env :prod
              ::http/routes routes
              ::http/type :jetty
              ::http/port 8080
              ;; we need this so we can use inline scripts
              ::http/secure-headers {:content-security-policy-settings {:object-src "none"}}})
;; end::html-home[]

;; tag::counter-route[]
(defn stream-ready [event-chan context]
  (dotimes [i 10]
    (when-not (chan/closed? event-chan)
      (async/>!! event-chan {:name "counter" :data i})
      (Thread/sleep 1000)))
  (async/close! event-chan))

(def routes
  `[[["/" {:get home-page}
      ["/counter" {:get [::send-counter (sse/start-event-stream stream-ready)]}]]]])
;; end::counter-route[]
;; end::add-sse-counter[]
