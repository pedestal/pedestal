; Copyright 2024 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.specs
  (:require [clojure.spec.alpha :as s]
            [io.pedestal.http :as http]
            [io.pedestal.http.impl.servlet-interceptor.specs :as servlet-interceptor]
            [io.pedestal.interceptor.specs :as interceptor]
            [io.pedestal.http.route.specs :as route]
            [io.pedestal.websocket.specs :as ws]))

(s/def ::http/service-map
  (s/keys :req [::http/port]
          :opt [::http/type
                ::http/host
                ::http/join?
                ::http/container-options
                ::http/websockets
                ::http/interceptors
                ;; These are placed here when the service is created (e.g., io.pedestal.jetty/service)
                ::http/start-fn
                ::http/stop-fn
                ;; From here down is essentially just what the default-interceptors function needs
                ::http/request-logger
                ::http/router
                ::http/file-path
                ::http/resource-path
                ::http/method-param-name
                ::http/allowed-origins
                ::http/not-found-interceptor
                ::http/mime-types
                ::http/enable-session
                ::http/enable-csrf
                ::http/secure-headers
                ::http/path-params-decoder
                ::http/initial-context
                ::http/service-fn-options
                ::http/tracing]))

(s/def ::http/port pos-int?)
(s/def ::http/type (s/or :fn fn?
                         :kw simple-keyword?))
(s/def ::http/host string?)
(s/def ::http/join? boolean?)
;; Each container will define its own container-options schema:
(s/def ::http/container-options map?)
(s/def ::http/websockets ::ws/websockets-map)
(s/def ::http/interceptors ::interceptor/interceptors)

(s/def ::http/request-logger ::interceptor/interceptor)
(s/def ::http/routes (s/or :protocol ::route/route-specification
                           :fn fn?
                           :nil nil?
                           ;; TODO: Shouldn't this be caught by the ExpandableRoutes check?
                           :maps (s/coll-of map?)))
(s/def ::http/resource-path string?)
(s/def ::http/method-param-name string?)
(s/def ::http/allowed-origins (s/or :strings (s/coll-of string?)
                                    :fn fn?
                                    ;; io.pedestal.http.cors/allow-origin has more details
                                    :map map?))
(s/def ::http/not-found-interceptor ::interceptor/interceptor)
(s/def ::http/mime-types (s/map-of string? string?))
;; See io.pedestal.http.ring-middlewares/session for more details
(s/def ::http/enable-session map?)
;; See io.pedestal.http.body-params/body-params for more details
(s/def ::http/enable-csrf map?)
;; See io.pedestal.http.secure-headers/secure-headers for more details
(s/def ::http/secure-headers map?)
(s/def ::http/path-params-decoder ::interceptor/interceptor)
(s/def ::http/initial-context map?)
(s/def ::http/tracing ::interceptor/interceptor)

(s/def ::http/service-fn-options ::servlet-interceptor/http-interceptor-service-fn-options)
(s/def ::http/start-fn fn?)
(s/def ::http/stop-fn fn?)

;; Used when starting Pedestal embedded

(s/fdef http/create-server
        :args (s/cat :service-map ::http/service-map
                     :init-fn (s/? fn?))
        :ret ::http/service-map)

(s/fdef http/create-servlet
        :args (s/cat :service-map ::http/service-map)
        :ret ::http/service-map)
