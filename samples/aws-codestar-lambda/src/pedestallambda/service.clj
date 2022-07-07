(ns pedestallambda.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-utils]
            [io.pedestal.interceptor :as interceptor]
            [cheshire.core :as json]
            [ring.util.response :as ring-resp])
  (:import (java.io ByteArrayOutputStream)))

;; PATCHING
;; ------------------------------------------
;;
;; `reduce-kv` doesn't drop back to generic `java.util.Map` support
;; like `reduce` and other sequence-oriented functions in clojure.core.
;; This is a known issue in Clojure: https://dev.clojure.org/jira/browse/CLJ-1762
;;
;; This is needed because Pedestal uses `reduce-kv` when processing Map-like
;; requests and APIGW uses java.util.Maps.
;;
;; THIS SHOULD BE REMOVED ONCE CLOJURE IS PATCHED!
;; ----
(extend-protocol clojure.core.protocols/IKVReduce
  java.util.Map
  (kv-reduce
    [amap f init]
    (let [^java.util.Iterator iter (.. amap entrySet iterator)]
      (loop [ret init]
        (if (.hasNext iter)
          (let [^java.util.Map$Entry kv (.next iter)
                ret (f ret (.getKey kv) (.getValue kv))]
            (if (reduced? ret)
              @ret
              (recur ret)))
          ret)))))
;; ------------------------------------------


;; Interceptors
;; ------------------------------------------
(def pre-body-params
  "An interceptor that ensures `:content-type` is on the Request map/object.
  This is achieved by first looking to see if the key already exists, and if not,
  adding the information as reported in the Content-Type header.
  This is necessary because `body-params` expects `:content-type`, but some
  chain providers (like APIGW) do the bare minimum when making the request map/object."
  (interceptor/interceptor
    {:name  ::pre-body-params
     :enter (fn [ctx]
              (if (get-in ctx [:request :content-type])
                ctx
                (assoc-in ctx [:request :content-type] (or (get-in ctx [:request :headers "content-type"])
                                                           (get-in ctx [:request :headers "Content-Type"])))))}))

(def json-body
  "Set the Content-Type header to \"application/json\" and convert the body to
  JSON if the body is a collection and a content type has not been set.

  This is a version of the standard `json-body` interceptor,
  except that if it detects it's running in Lambda/APIGA mode,
  will eagerly produce the JSON string (instead of a function that works on an OutputStream)"
  (interceptor/interceptor
    {:name ::json-body
     :leave (fn [ctx]
              (if (contains? ctx :aws.apigw/event)
                (let [response (:response ctx)
                      body (:body response)
                      content-type (get-in response [:headers "Content-Type"])]
                  (assoc ctx :response
                             (if (and (coll? body) (not content-type))
                               (-> response
                                   (ring-resp/content-type "application/json;charset=UTF-8")
                                   (assoc :body (json/generate-string body)))
                               response)))
                ((:leave http/json-body) ctx)))}))


;; Service functionality
;; ------------------------------------------

;; Our service returns EDN that is coerced and sent as JSON bodies over HTTP (via API Gateway)

(defn about-page
  [request]
  (ring-resp/response {:msg (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about-page))}))

(defn home-page
  [request]
  (ring-resp/response {:msg "Hello World!"
                       :params (:params request)}))

;; Defines "/" and "/about" routes with their associated :get handlers.
;; The interceptors defined after the verb map (e.g., {:get home-page}
;; apply to / and its children (/about).
(def common-interceptors [pre-body-params (body-params/body-params) json-body])

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


;; Consumed by pedestal-lambda.server/create-server
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
                                        :ssl? false

                                        ;; Additional options for API Gateway
                                        :body-processor (fn [body]
                                                          ;; We expect all bodies to be JSON strings by this point,
                                                          ;; but this ensures backwards compatibility
                                                          ;; with Pedestal's/Servlet's OutputStream-based model
                                                          ;; (which some interceptors might require)
                                                           (if (string? body)
                                                            body
                                                            (->> (ByteArrayOutputStream.)
                                                                 (servlet-utils/write-body-to-stream body)
                                                                 (.toString))))}})

