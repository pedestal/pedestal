; Copyright 2023-2025 Nubank NA
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
            [io.pedestal.http.response :as response]
            [io.pedestal.http.servlet :as servlet]
            [io.pedestal.internal :refer [deprecated with-deprecations-suppressed]]
            [io.pedestal.http.impl.servlet-interceptor :as si]
            [io.pedestal.service.protocols :as p]
            [io.pedestal.connector.test :as test]
            [io.pedestal.websocket :as ws])
  (:import (jakarta.websocket.server ServerContainer)
           (org.eclipse.jetty.ee10.servlet ServletContextHandler ServletHolder)
           (org.eclipse.jetty.http2 HTTP2Cipher)
           (org.eclipse.jetty.http2.api.server ServerSessionListener)
           (org.eclipse.jetty.http2.server HTTP2CServerConnectionFactory RawHTTP2ServerConnectionFactory)
           (org.eclipse.jetty.server ConnectionFactory
                                     Server
                                     HttpConfiguration
                                     SecureRequestCustomizer
                                     HttpConnectionFactory
                                     ServerConnector
                                     SslConnectionFactory)
           (org.eclipse.jetty.util.thread QueuedThreadPool ThreadPool)
           (org.eclipse.jetty.util.ssl SslContextFactory SslContextFactory$Server KeyStoreScanner)
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
        (.setCipherComparator context HTTP2Cipher/COMPARATOR)
        (.setUseCipherSuitesOrder context true)
        context)))

(defn- ssl-conn-factory
  "Create an SslConnectionFactory instance."
  [server options]
  (let [{:keys [alpn container-options]}    options
        {:keys [keystore-scal-interval]
         :or   {keystore-scal-interval 60}} container-options
        ssl-context                         (ssl-context-factory container-options)
        factory                             (SslConnectionFactory. ssl-context (if alpn
                                                                                 (.getProtocol ^ALPNServerConnectionFactory alpn)
                                                                                 "http/1.1"))]
    (.addBean server (doto (KeyStoreScanner. ssl-context)
                       (.setScanInterval keystore-scal-interval)))
    factory))

(defn- http-configuration
  "Provides an HttpConfiguration that can be consumed by connection factories.
  The `:http-configuration` option can be used to specify
  your own HttpConfiguration instance."
  ^HttpConfiguration [options]
  (or (:http-configuration options)
      ;; In 0.7 and earlier, was a namespaced key
      (::http-configuration options)
      ;; Otherwise, build on the fly
      (let [{:keys [sni-host-check? insecure-ssl?]} options
            http-conf ^HttpConfiguration (HttpConfiguration.)
            sni-enabled? (cond
                           (and (nil? sni-host-check?)
                                (nil? insecure-ssl?))   true
                           (not (nil? sni-host-check?)) sni-host-check?
                           :else                        (not insecure-ssl?))]
        (when insecure-ssl?
          (deprecated :insecure-ssl? :in "0.8.0" :noun ":insecure-ssl? Jetty option"))
        (doto http-conf
          (.setSendDateHeader true)
          (.setSendXPoweredBy false)
          (.setSendServerVersion false)
          ;; :sni-host-check? Perform Server Name Indication check during TLS handshake (default true). Set this to false for local development and when
          ;; using DNS wildcard certificates since "localhost" is not a valid server name according to the TLS spec and the definitive
          ;; server name is not known when using a DNS wildcard certificate after the TLS handshake. You will receive HTTP 400 statuses
          ;; from the browser when this is enabled and you use localhost or a DNS wildcard certificate.
          (.addCustomizer (SecureRequestCustomizer. sni-enabled?))))))

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
  ^ServerConnector [^Server server factories]
  (let [factories' (into-array ConnectionFactory (remove nil? factories))
        conn       (ServerConnector. server factories')]
    (.addConnector server conn)
    ;; Return the ServerConnector for any further configuration
    conn))

;; Consider allowing users to set the number of acceptors (ideal at 1 per core) and/or selectors
(defn- create-server
  "Construct a Jetty Server instance."
  ^Server [servlet options]
  (let [{:keys [host port websockets container-options]} options
        {:keys [ssl? ssl-port max-streams
                h2? h2c? connection-factory-fns
                context-configurator context-path configurator daemon? reuse-addr?
                ws-idle-timeout ws-max-text-size ws-max-binary-size]
         :or   {configurator identity
                context-path "/"
                h2c?         true
                max-streams  128
                reuse-addr?  true}} container-options
        ^ThreadPool thread-pool (thread-pool options)
        server                  (Server. thread-pool)
        _                       (do
                                  (when-not (string/starts-with? context-path "/")
                                    (throw (ex-info "context-path must begin with a '/'" {:container-options container-options})))

                                  (when (and h2? (not ssl-port))
                                    (throw (ex-info "SSL must be enabled to use HTTP/2; Provide keys :ssl-port and keystore/truststore configuration"
                                                    {:container-options container-options})))

                                  (when (and (nil? port) (not (or ssl? ssl-port h2?)))
                                    (throw (ex-info "No HTTP or SSL port configured"
                                                    {:container-options container-options})))

                                  (when (and (nil? port)
                                             h2c?)
                                    (throw (ex-info "HTTP2-Cleartext can not be enabled unless a non-nil HTTP port is provided"
                                                    {:container-options container-options}))))
        server-session-listener (reify ServerSessionListener)
        http-conf               (http-configuration container-options)
        http                    (HttpConnectionFactory. http-conf)
        http2c                  (when h2c?
                                  (doto (HTTP2CServerConnectionFactory. http-conf)
                                    (.setMaxConcurrentStreams max-streams)))
        http2                   (when h2?
                                  (doto (RawHTTP2ServerConnectionFactory. server-session-listener)
                                    (.setMaxConcurrentStreams max-streams)
                                    (.setConnectProtocolEnabled true)))
        alpn                    (when h2?
                                  ;; Application-Layer Protocol Negotiation
                                  (doto (ALPNServerConnectionFactory. "h2,h2-17,h2-14,http/1.1")
                                    (.setDefaultProtocol "http/1.1")))
        ssl                     (when (or ssl? ssl-port h2?)
                                  (ssl-conn-factory server (assoc options :alpn alpn)))
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
      ;; Always initialize the container for WebSockets, even when no :websockets key, to ensure that
      ;; routed websockets also work.
      (JakartaWebSocketServletContainerInitializer/configure servlet-context-handler
                                                             (reify JakartaWebSocketServletContainerInitializer$Configurator
                                                               (^void accept [_this ^ServletContext _context
                                                                              ^ServerContainer container]
                                                                 (when ws-idle-timeout
                                                                   (.setDefaultMaxSessionIdleTimeout container ws-idle-timeout))
                                                                 (when ws-max-text-size
                                                                   (.setDefaultMaxTextMessageBufferSize container ws-max-text-size))
                                                                 (when ws-max-binary-size
                                                                   (.setDefaultMaxBinaryMessageBufferSize container ws-max-binary-size))
                                                                 (when websockets
                                                                   (deprecated ::websockets
                                                                     :in "0.8.0"
                                                                     :noun "non-routed websockets (via the :io.pedestal.http/websockets service map key)")
                                                                   (with-deprecations-suppressed
                                                                     (ws/add-endpoints container websockets))))))
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


(defn- start
  [^Server server
   {:keys [join?] :or {join? true}}]
  (.start server)
  (when join? (.join server))
  server)

(defn- stop
  [^Server server]
  (.stop server)
  server)

(defn server
  "Called from [[io.pedestal.http/server]] to create a Jetty server instance.
   The container option :insecure-ssl? is deprecate in favor of :sni-host-check?
   which is more precise but inverse to the deprecated key."
  [service-map options]
  (let [server (create-server (:io.pedestal.http/servlet service-map) options)]
    {::server  server
     :start-fn #(start server options)
     :stop-fn  #(stop server)}))


;; io.pedestal.http does a bit of inversion of control to create the service fn and servlet
;; before invoking server; in the new model (io.pedestal.service) the service is responsible
;; for this itself.

(defn create-connector
  "Creates a connector from the service map and the connector-specific options.

  Returns a connector in an unstarted state."
  [service-map options]
  (let [{:keys [interceptors initial-context join?]} service-map
        ;; The options may include an :exception-analyzer function.
        service-fn        (si/http-interceptor-service-fn interceptors initial-context options)
        servlet           (servlet/servlet :service service-fn)
        ;; Mixing service-map and options; another bit of relic that maybe can be fixed
        ;; with changes to io.pedestal.http (that are probably ok to do as it only concerns implementation
        ;; details).
        server            (create-server servlet (merge service-map options))
        ;; Normally, apply-default-content-type is provided by http-interceptor-service-fn, but since
        ;; we are bypassing that for testing, need to explicitly add it back in.
        test-interceptors (into [si/apply-default-content-type] interceptors)
        test-context      (response/terminate-when-response initial-context)]
    (reify
      p/PedestalConnector

      (start-connector! [this]
        (.start server)
        (when join?
          (.join server))
        this)

      (stop-connector! [this]
        (.stop server)
        this)

      (test-request [_ request]
        (test/execute-interceptor-chain test-context test-interceptors request)))))

