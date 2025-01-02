(ns io.pedestal.http.jetty.specs
  (:require [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http :as http]
            io.pedestal.http.specs
            [io.pedestal.internal :refer [is-a]]
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
(s/def ::connection-factory-fns (s/coll-of fn?))
(s/def ::ssl-context-factory (is-a SslContextFactory))
(s/def ::keystore ::string-or-keystore)
(s/def ::truststore ::string-or-keystore)
(s/def ::http-configuration (is-a HttpConfiguration))
(s/def ::security-provider string?)
(s/def ::client-auth #{:need :want :none})


(s/fdef jetty/service
        :args (s/cat
                :service-map ::http/service-map
                :options (s/and ::http/server-options
                            ;; "Refine" the :container-options key for Jetty-specific options
                            (s/keys :opt-un [::container-options])))
        :ret ::http/container-lifecycle)

