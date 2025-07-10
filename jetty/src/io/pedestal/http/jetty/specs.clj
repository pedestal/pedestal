; Copyright 2024-2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.jetty.specs
  (:require [io.pedestal.http.jetty :as jetty]
            io.pedestal.http.specs
            [io.pedestal.http :as http]
            [io.pedestal.internal :refer [is-a]]
            [io.pedestal.connector.specs :as connector]
            [clojure.spec.alpha :as s])
  (:import (java.security KeyStore)
           (org.eclipse.jetty.server HttpConfiguration)
           (org.eclipse.jetty.util.ssl SslContextFactory)
           (org.eclipse.jetty.util.thread ThreadPool)))

(s/def ::container-options
  (s/keys :opt-un [::daemon?
                   ::max-threads
                   ::max-streams
                   ::reuse-addr?
                   ::configurator
                   ::context-configurator
                   ::context-path
                   ::ssl?
                   ::insecure-ssl?
                   ::sni-host-check?
                   ::ssl-port
                   ::h2?
                   ::h2c?
                   ::connection-factory-fns
                   ::ssl-context-factory
                   ::keystore
                   ::key-password
                   ::truststore
                   ::trust-password
                   ::client-auth
                   ::security-provider
                   ::http-configuration]))

(s/def ::string-or-keystore (s/or :string string?
                                  :object (is-a KeyStore)))

(s/def ::daemon? boolean?)
(s/def ::max-threads pos-int?)
(s/def ::max-streams pos-int?)
(s/def ::reuse-addr? boolean?)
(s/def ::thread-pool (is-a ThreadPool))
(s/def ::configurator fn?)
(s/def ::context-configurator fn?)
(s/def ::context-path string?)
(s/def ::ssl? boolean?)
(s/def ::ssl-port pos-int?)
(s/def ::h2c? boolean?)
(s/def ::h2? boolean?)
(s/def ::insecure-ssl? boolean?)
(s/def ::sni-host-check? boolean?)
(s/def ::connection-factory-fns (s/coll-of fn?))
(s/def ::ssl-context-factory (is-a SslContextFactory))
(s/def ::keystore ::string-or-keystore)
(s/def ::truststore ::string-or-keystore)
(s/def ::http-configuration (is-a HttpConfiguration))
(s/def ::security-provider string?)
(s/def ::client-auth #{:need :want :none})


(s/fdef jetty/server
        :args (s/cat
                :service-options ::http/container-options
                :container-options (s/and ::http/container-options
                            ;; "Refine" the :container-options key for Jetty-specific options
                            (s/keys :opt-un [::container-options])))
        :ret ::http/container-lifecycle)

(s/fdef jetty/create-connector
  :args (s/cat :connector-map ::connector/connector-map
               :options (s/nilable ::container-options)))

