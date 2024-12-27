; Copyright 2023-2024 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.jetty
  "Jetty adaptor for Pedestal."
  (:require io.pedestal.http.jetty.container
            [clojure.string :as string]
            [io.pedestal.websocket :as ws])
  (:import (jakarta.websocket.server ServerContainer)
           (org.eclipse.jetty.ee10.servlet ServletContextHandler ServletHolder)
           (org.eclipse.jetty.http2.api.server ServerSessionListener)
           (org.eclipse.jetty.http2.server RawHTTP2ServerConnectionFactory)
           (org.eclipse.jetty.server ConnectionFactory
                                     Server
                                     HttpConfiguration
                                     SecureRequestCustomizer
                                     HttpConnectionFactory
                                     ServerConnector
                                     SslConnectionFactory)
           (org.eclipse.jetty.util.thread QueuedThreadPool ThreadPool)
           (org.eclipse.jetty.util.ssl SslContextFactory SslContextFactory$Server)
           (org.eclipse.jetty.alpn.server ALPNServerConnectionFactory)
           (jakarta.servlet Servlet ServletContext)
           (java.security KeyStore)
           (org.eclipse.jetty.ee10.websocket.jakarta.server.config JakartaWebSocketServletContainerInitializer JakartaWebSocketServletContainerInitializer$Configurator)))

;; Implement any container specific optimizations from Pedestal's container protocols

(defn- ssl-context-factory
  "Creates a new SslContextFactory instance from a map of options."
  ^SslContextFactory [options]
  (or (:ssl-context-factory options)
      (let [{:keys [^KeyStore keystore key-password
                    ^KeyStore truststore
                    ^String trust-password
                    ^String security-provider
                    client-auth]} options
            ^SslContextFactory$Server context (SslContextFactory$Server.)]
        (when (every? nil? [keystore key-password truststore trust-password client-auth])
          (throw (IllegalArgumentException. "You are attempting to use SSL, but you did not supply any certificate management (KeyStore/TrustStore/etc.)")))
        (if (string? keystore)
          (.setKeyStorePath context keystore)
          (.setKeyStore context keystore))
        (.setKeyStorePassword context key-password)
        (when truststore
          (if (string? truststore)
            (.setTrustStorePath context truststore)
            (.setTrustStore context truststore)))
        (when trust-password
          (.setTrustStorePassword context trust-password))
        (when security-provider
          (.setProvider context security-provider))
        (case client-auth
          :need (.setNeedClientAuth context true)
          :want (.setWantClientAuth context true)
          nil)
        #_(.setCipherComparator context HTTP2Cipher/COMPARATOR)
        (.setUseCipherSuitesOrder context true)
        context)))

(defn- ssl-conn-factory
  "Create an SslConnectionFactory instance."
  [options]
  (let [{:keys [alpn container-options]} options]
    (SslConnectionFactory.
      (ssl-context-factory container-options)
      (if alpn
        (.getProtocol ^ALPNServerConnectionFactory alpn)
        "http/1.1"))))

(defn- http-configuration
  "Provides an HttpConfiguration that can be consumed by connection factories.
  The `:http-configuration` option can be used to specify
  your own HttpConfiguration instance."
  ^HttpConfiguration [options]
  (if-let [http-conf-override ^HttpConfiguration (or (:http-configuration options)
                                                     ;; In 0.7 and earlier, was a namespaced key
                                                     (::http-configuration options))]
    http-conf-override
    (let [{:keys [insecure-ssl?]} options
          http-conf ^HttpConfiguration (HttpConfiguration.)]
      (doto http-conf
        (.setSendDateHeader true)
        (.setSendXPoweredBy false)
        (.setSendServerVersion false)
        ;; :insecure-ssl? is useful for local development, as otherwise "localhost"
        ;; is not allowed as a valid host name, resulting in 400 statuses.
        (.addCustomizer (SecureRequestCustomizer. (not insecure-ssl?)))))))

(defn- needed-pool-size
  "Jetty calculates a needed number of threads per acceptors and selectors,
  based on the available cores on a given machine.
  This performs that calculation.  This is used to ensure an appropriate
  number of threads are created for the server."
  ([]
   (let [cores     (.availableProcessors (Runtime/getRuntime))
         ;; The Jetty docs claim acceptors is 1.5 the number of cores available,
         ;; but the code says:  1 + cores / 16 - https://github.com/eclipse/jetty.project/blob/master/jetty-server/src/main/java/org/eclipse/jetty/server/AbstractConnector.java#L192
         acceptors (int (* 1.5 cores))                      ;(inc (/ cores 16))
         selectors (/ (inc cores) 2.0)]                     ; (cores + 1) / 2 - https://github.com/eclipse/jetty.project/blob/master/jetty-io/src/main/java/org/eclipse/jetty/io/SelectorManager.java#L73
     ;; 2 connectors - HTTP & HTTPS
     (needed-pool-size 2 acceptors selectors)))
  ([connectors acceptors selectors]
   (* (Math/round ^Double (+ acceptors selectors)) connectors)))

(defn- thread-pool
  "Returns a thread pool for the Jetty server. Can be overridden
  with [:container-options :thread-pool] in options. max-threads is
  ignored if the pool is overridden."
  [{{:keys [max-threads thread-pool]
     :or   {max-threads (max 50 (needed-pool-size))}}
    :container-options}]
  (or thread-pool
      (QueuedThreadPool. ^Integer max-threads)))

(defn- add-connection-factories
  [^Server server factories]
  (let [factories' (into-array ConnectionFactory (remove nil? factories))
        conn (ServerConnector. server factories')]
    (.addConnector server conn)
    ;; Return the ServerConnector for any further configuration
    conn))

;; Consider allowing users to set the number of acceptors (ideal at 1 per core) and/or selectors
(defn create-server
  "Construct a Jetty Server instance."
  [servlet options]
  (let [{:keys [host port websockets container-options]} options
        {:keys [ssl? ssl-port
                h2? h2c? connection-factory-fns
                context-configurator context-path configurator daemon? reuse-addr?]
         :or   {configurator identity
                context-path "/"
                h2c?         true
                reuse-addr?  true}} container-options
        ^ThreadPool thread-pool (thread-pool options)
        server                  (Server. thread-pool)
        _                       (when-not (string/starts-with? context-path "/")
                                  (throw (IllegalArgumentException. "context-path must begin with a '/'")))
        _                       (when (and h2? (not ssl-port))
                                  (throw (IllegalArgumentException. "SSL must be enabled to use HTTP/2. Please set an ssl port and appropriate *store setups")))
        _                       (when (and (nil? port) (not (or ssl? ssl-port h2?)))
                                  (throw (IllegalArgumentException. "HTTP was turned off with a `nil` port value, but no SSL config was supplied.  Please set an HTTP port or configure SSL")))
        _                       (when (and (nil? port) (true? h2c?))
                                  (throw (IllegalArgumentException. "HTTP was turned off with a `nil` port value, but you attempted to turn on HTTP2-Cleartext.  Please set an HTTP port or set `h2c?` to false in your service config")))
        server-session-listener (reify ServerSessionListener)
        http-conf               (http-configuration container-options)
        http                    (HttpConnectionFactory. http-conf)
        http2c                  (when h2c? #_(HTTP2CServerConnectionFactory. http-conf))
        http2                   (when h2?
                                  (doto (RawHTTP2ServerConnectionFactory. server-session-listener)
                                    ;; TODO: Max concurrent streams
                                    (.setConnectProtocolEnabled true)))
        alpn                    (when h2?
                                  ;(NegotiatingServerConnectionFactory/checkProtocolNegotiationAvailable) ;; This only looks at Java8 bootclasspath stuff, and is no longer valid in newer Jetty versions
                                  (doto (ALPNServerConnectionFactory. "h2,h2-17,h2-14,http/1.1")
                                    (.setDefaultProtocol "http/1.1")))
        ssl                     (when (or ssl? ssl-port h2?)
                                  (ssl-conn-factory (assoc options :alpn alpn)))
        http-connector          (when port
                                  (doto (add-connection-factories server [http http2c])
                                    (.setReuseAddress reuse-addr?)
                                    (.setPort port)
                                    (.setHost host)))
        ssl-connector           (when ssl
                                  (let [factories (into [ssl alpn http2 (HttpConnectionFactory. http-conf)]
                                                        (map (fn [ffn] (ffn options http-conf)) connection-factory-fns))]
                                    (doto (add-connection-factories server factories)
                                      (.setReuseAddress reuse-addr?)
                                      (.setPort ssl-port)
                                      (.setHost host))))
        servlet-context-handler (doto (ServletContextHandler. ServletContextHandler/SESSIONS)
                                  (.setContextPath context-path)
                                  (.addServlet (ServletHolder. ^Servlet servlet) "/*"))]
    (when websockets
      (JakartaWebSocketServletContainerInitializer/configure servlet-context-handler
                                                             (reify JakartaWebSocketServletContainerInitializer$Configurator
                                                               (^void accept [_this ^ServletContext _context
                                                                              ^ServerContainer container]
                                                                 (ws/add-endpoints container websockets)))))
    (when daemon?
      ;; Reflective; it is up to the caller to ensure that the thread-pool has a daemon boolean property if
      ;; :daemon? flag is true.
      (.setDaemon thread-pool true))
    (when http-connector
      (.addConnector server http-connector))
    (when ssl-connector
      (.addConnector server ssl-connector))
    (when context-configurator
      (context-configurator servlet-context-handler))

    ;; Only set the handler once it is fully configured
    (.setDefaultHandler server servlet-context-handler)

    ;; And only after that perform final configuration of the server
    (configurator server)))


(defn- -start
  [^Server server
   {:keys [join?] :or {join? true}}]
  (.start server)
  (when join? (.join server))
  server)

(defn- -stop [^Server server]
  (.stop server)
  server)

(defn server
  "Called from [[io.pedestal.http/server]] to create a Jetty server instance."
  ([service-map] (server service-map {}))
  ([service-map options]
   (let [server (create-server (:io.pedestal.http/servlet service-map) options)]
     {:server   server
      :start-fn #(-start server options)
      :stop-fn  #(-stop server)})))
