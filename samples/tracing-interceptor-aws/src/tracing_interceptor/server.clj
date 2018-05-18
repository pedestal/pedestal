(ns tracing-interceptor.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.http :as server]
            [io.pedestal.http.route :as route]
            [io.pedestal.log :as log]
            [tracing-interceptor.service :as service])
  (:import (com.amazonaws.xray AWSXRayRecorderBuilder)))

;; This is an adapted service map, that can be started and stopped
;; From the REPL you can call server/start and server/stop on this service
(defonce runnable-service (server/create-server service/service))

(defn run-dev
  "The entry-point for 'lein run-dev'"
  [& args]
  ;; There are multiple ways to set the default tracer referenced in `pedestal.log`.
  ;; If the OpenTracing GlobalTracer is still unregistered (ie: it's a NoopTracer),
  ;;  we can just use the -register protocol function to register our Tracer before
  ;;  any other Tracer could be registered.  This approach will only fail if
  ;;  the Tracer is set using the JVM property or environment variable.
  (println "Registering local Trace connection")
  (log/-register (.build (AWSXRayRecorderBuilder/standard)))
  (println "\nCreating your [DEV] server...")
  (-> service/service ;; start with production configuration
      (merge {:env :dev
              ;; do not block thread that starts web server
              ::server/join? false
              ;; Routes can be a function that resolve routes,
              ;;  we can use this to set the routes to be reloadable
              ::server/routes #(route/expand-routes (deref #'service/routes))
              ;; all origins are allowed in dev mode
              ::server/allowed-origins {:creds true :allowed-origins (constantly true)}
              ;; Content Security Policy (CSP) is mostly turned off in dev mode
              ::server/secure-headers {:content-security-policy-settings {:object-src "'none'"}}})
      ;; Wire up interceptor chains
      server/default-interceptors
      server/dev-interceptors
      server/create-server
      server/start))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  ;; There are multiple ways to set the default tracer referenced in `pedestal.log`.
  ;; If the OpenTracing GlobalTracer is still unregistered (ie: it's a NoopTracer),
  ;;  we can just use the -register protocol function to register our Tracer before
  ;;  any other Tracer could be registered.  This approach will only fail if
  ;;  the Tracer is set using the JVM property or environment variable.
  (println "Registering local Trace connection")
  (log/-register (.build (AWSXRayRecorderBuilder/standard)))
  (println "\nCreating your server...")
  (server/start runnable-service))

;; If you package the service up as a WAR,
;; some form of the following function sections is required (for io.pedestal.servlet.ClojureVarServlet).

;;(defonce servlet  (atom nil))
;;
;;(defn servlet-init
;;  [_ config]
;;  ;; Initialize your app here.
;;  (reset! servlet  (server/servlet-init service/service nil)))
;;
;;(defn servlet-service
;;  [_ request response]
;;  (server/servlet-service @servlet request response))
;;
;;(defn servlet-destroy
;;  [_]
;;  (server/servlet-destroy @servlet)
;;  (reset! servlet nil))

