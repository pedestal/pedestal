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
            [io.pedestal.http.impl.servlet-interceptor.specs :as servlet-interceptor]
            [io.pedestal.interceptor.specs :as interceptor]
            [io.pedestal.http.route.specs :as route]
            [io.pedestal.websocket.specs :as ws]))

(s/def ::service-map
  (s/keys :req [::port]
          :opt [::type
                ::host
                ::join?
                ::container-options
                ::websockets
                ::interceptors
                ;; These are placed here when the service is created (e.g., io.pedestal.jetty/service)
                ::start-fn
                ::stop-fn
                ;; From here down is essentially just what the default-interceptors function needs
                ::request-logger
                ::router
                ::file-path
                ::resource-path
                ::method-param-name
                ::allowed-origins
                ::not-found-interceptor
                ::mime-types
                ::enable-session
                ::enable-csrf
                ::secure-headers
                ::path-params-decoder
                ::initial-context
                ::service-fn-options
                ::tracing]))

(s/def ::port pos-int?)
(s/def ::type (s/or :fn fn?
                    :kw simple-keyword?))
(s/def ::host string?)
(s/def ::join? boolean?)
;; Each container will define its own container-options schema:
(s/def ::container-options map?)
(s/def ::websockets ::ws/websockets-map)
(s/def ::interceptors ::interceptor/interceptors)

(s/def ::request-logger ::interceptor/interceptor)
(s/def ::routes (s/or :protocol ::route/route-specification
                      :fn fn?
                      :nil nil?
                      ;; TODO: Shouldn't this be caught by the ExpandableRoutes check?
                      :maps (s/coll-of map?)))
(s/def ::resource-path string?)
(s/def ::method-param-name string?)
(s/def ::allowed-origins (s/or :strings (s/coll-of string?)
                               :fn fn?
                               ;; io.pedestal.http.cors/allow-origin has more details
                               :map map?))
(s/def ::not-found-interceptor ::interceptor/interceptor)
(s/def ::mime-types (s/map-of string? string?))
;; See io.pedestal.http.ring-middlewares/session for more details
(s/def ::enable-session map?)
;; See io.pedestal.http.body-params/body-params for more details
(s/def ::enable-csrf map?)
;; See io.pedestal.http.secure-headers/secure-headers for more details
(s/def ::secure-headers map?)
(s/def ::path-params-decoder ::interceptor/interceptor)
(s/def ::initial-context map?)
(s/def ::tracing ::interceptor/interceptor)

(s/def ::service-fn-options ::servlet-interceptor/http-interceptor-service-fn-options)
(s/def ::start-fn fn?)
(s/def ::stop-fn fn?)
