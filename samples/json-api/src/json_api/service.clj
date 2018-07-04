(ns json-api.service
  (:require [io.pedestal.interceptor :as interceptor]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]
            [cheshire.core :as json]))

;; Including Content-Length
;; -----------------------------
;;
;; Most response-oriented utilities in Pedestal support streaming operations.
;; It's often the case that these utilities are narrowly focused -- they focus
;; only on the streamed response and nothing else (they overstep control or signaling).
;;
;; However, sometimes you'll end up designing an API where all the response
;; bodies are finite, and you'll want to set headers important to your
;; application and/or consumers.
;; In that case, defining your own coercion interceptors are best.
;;
;; Here we define an interceptor to coerce `:body` into a JSON string,
;; and set both the Content-Type and Content-Length.
;;
;; NOTE: You can also tell your container to always set Content-Length,
;;       which is often a better option.
(def content-length-json-body
  (interceptor/interceptor
    {:name ::content-length-json-body
     :leave (fn [context]
              (let [response (:response context)
                    body (:body response)
                    json-response-body (if body (json/generate-string body) "")
                    ;; Content-Length is the size of the response in bytes
                    ;; Let's count the bytes instead of the string, in case there are unicode characters
                    content-length (count (.getBytes ^String json-response-body))
                    headers (:headers response {})]
                (assoc context
                       :response {:status (:status response)
                                  :body json-response-body
                                  :headers (merge headers
                                                  {"Content-Type" "application/json;charset=UTF-8"
                                                   "Content-Length" (str content-length)})})))}))

(defn about-page
  [request]
  ;; When using our `content-type-json-body` in the interceptor chain,
  ;; response bodies are just Clojure data that get turned into JSON strings
  (ring-resp/response {:some-data 1
                       :msg (format "Clojure %s - served from %s"
                                    (clojure-version)
                                    (route/url-for ::about-page))}))

(defn home-page
  [request]
  ;; When using the `json-body` in the interceptor chain,
  ;; response bodies are just Clojure data that *stream* into the response OutputStream (as UTF-8)
  (ring-resp/response {:hello "world"}))

(def common-interceptors [(body-params/body-params) http/json-body])
(def custom-interceptors [(body-params/body-params) content-length-json-body])

;; Tabular routes
(def routes #{["/" :get (conj common-interceptors `home-page)]
              ["/about" :get (conj custom-interceptors `about-page)]})

;; Consumed by json-api.server/create-server
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

