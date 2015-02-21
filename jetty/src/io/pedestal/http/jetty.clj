; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.jetty
  (:require [io.pedestal.http.jetty.container])
  (:import (org.eclipse.jetty.server Server ServerConnector
                                     Request
                                     HttpConfiguration
                                     ConnectionFactory
                                     HttpConnectionFactory
                                     SslConnectionFactory)
           (org.eclipse.jetty.server.handler AbstractHandler)
           (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (org.eclipse.jetty.util.thread QueuedThreadPool)
           (org.eclipse.jetty.util.ssl SslContextFactory)
           (javax.servlet Servlet)
           (java.security KeyStore)
           (javax.servlet.http HttpServletRequest HttpServletResponse)))

;; Implement any container specific optimizations from Pedestal's container protocols


;; The approach here is based on code from ring.adapter.jetty

;; The Jetty9 updates are based on http://www.eclipse.org/jetty/documentation/current/embedding-jetty.html
;; and http://download.eclipse.org/jetty/stable-9/xref/org/eclipse/jetty/embedded/ManyConnectors.html

(defn- ^SslContextFactory ssl-context-factory
  "Creates a new SslContextFactory instance from a map of options."
  [options]
  (let [{:keys [^KeyStore keystore key-password
                ^KeyStore truststore
                ^String trust-password
                client-auth
                ssl-include-protocols]} options
        context (SslContextFactory.)]
    (if (string? keystore)
      (.setKeyStorePath context keystore)
      (.setKeyStore context keystore))
    (.setKeyStorePassword context key-password)
    (when truststore
      (.setTrustStore context truststore))
    (when trust-password
      (.setTrustStorePassword context trust-password))
    (when ssl-include-protocols
      (.setIncludeProtocols context (into-array String ssl-include-protocols)))
    (case client-auth
      :need (.setNeedClientAuth context true)
      :want (.setWantClientAuth context true)
      nil)
    context))

(defn- ssl-connector
  "Creates a SslSelectChannelConnector instance."
  [^Server server options ^HttpConfiguration http-conf]
  (let [{host :host
         {:keys [ssl-port reuse-addr? alpn connection-factory-fns]
          :or {ssl-port 443
               reuse-addr? true
               connection-factory-fns []}
          :as container-options} :container-options} options
        connector (if alpn
                    (ServerConnector. server (into-array ConnectionFactory (into [(SslConnectionFactory. (ssl-context-factory container-options)
                                                                                                         "alpn")]
                                                                                 (map #(% options http-conf) connection-factory-fns))))
                    (ServerConnector. server (ssl-context-factory container-options)))]
    (doto connector
      (.setReuseAddress reuse-addr?)
      (.setPort ssl-port)
      (.setHost host))))

(defn- http-configuration
  "Provides an HttpConfiguration that can be consumed by connection factories"
  [options]
  (let [{:keys [ssl? ssl-port]} options
        http-conf ^HttpConfiguration (HttpConfiguration.)]
    (when ssl?
      (.setSecurePort http-conf ssl-port))
    (doto http-conf
      (.setSendDateHeader true))))

(defn- needed-pool-size
  "Jetty 9 calculates a needed number of threads per acceptors and selectors,
  based on the available cores on a given machine.
  This performs that calculation.  This is used to ensure an appropriate
  number of threads are created for the server."
  ([]
   (let [cores (.availableProcessors (Runtime/getRuntime))
         ;; The Jetty docs claim acceptors is 1.5 the number of cores available,
         ;; but the code says:  1 + cores / 16 - https://github.com/eclipse/jetty.project/blob/master/jetty-server/src/main/java/org/eclipse/jetty/server/AbstractConnector.java#L192
        acceptors (int (* 1.5 cores)) ;(inc (/ cores 16))
        selectors (/ (inc cores) 2.0)] ; (cores + 1) / 2 - https://github.com/eclipse/jetty.project/blob/master/jetty-io/src/main/java/org/eclipse/jetty/io/SelectorManager.java#L73
   ;; 2 connectors - HTTP & HTTPS
   (needed-pool-size 2 acceptors selectors)))
  ([connectors acceptors selectors]
   (* (Math/round ^Double (+ acceptors selectors)) connectors)))

;; Consider allowing users to set the number of acceptors (ideal at 1 per core) and/or selectors
(defn- create-server
  "Construct a Jetty Server instance."
  [servlet options]
  (let [{host :host
         port :port
         {:keys [ssl? ssl-port alpn context-configurator configurator max-threads daemon? reuse-addr?]
          :or {configurator identity
               max-threads (max 50 (needed-pool-size))
               reuse-addr? true}} :container-options} options
        thread-pool (QueuedThreadPool. ^Integer max-threads)
        server (Server. thread-pool)
        http-conf (http-configuration (:container-options options))
        connector (doto (ServerConnector. server)
                    (.addConnectionFactory (HttpConnectionFactory. http-conf))
                    (.setReuseAddress reuse-addr?)
                    (.setPort port)
                    (.setHost host))
        ssl-conn (when (or ssl? ssl-port alpn)
                   (ssl-connector server options http-conf))
        context (doto (ServletContextHandler. server "/")
                  (.addServlet (ServletHolder. ^javax.servlet.Servlet servlet) "/*"))]
    (when daemon?
      (.setDaemon thread-pool true))
    (when connector
      (.addConnector server connector))
    (when ssl-conn
      (.addConnector server ssl-conn))
    (when context-configurator
      (context-configurator context))
    (configurator server)))

(defn start
  [^Server server
   {:keys [join?] :or {join? true} :as options}]
  (.start server)
  (when join? (.join server))
  server)

(defn stop [^Server server]
  (.stop server)
  server)

(defn server
  ([servlet] (server servlet {}))
  ([servlet options]
     (let [server (create-server servlet options)]
       {:server   server
        :start-fn #(start server options)
        :stop-fn  #(stop server)})))


  ;; :port         - the port to listen on (defaults to 80)
  ;; :host         - the hostname to listen on
  ;; :join?        - blocks the thread until server ends (defaults to true)

  ;; :daemon?      - use daemon threads (defaults to false)
  ;; :max-threads  - the maximum number of threads to use (default 50)
  ;; :resue-addr?  - reuse the socket address (defaults to true)
  ;; :configurator - a function called with the Jetty Server instance
  ;; :context-configurator - a function called with the Jetty ServletContextHandler
  ;; :ssl?         - allow connections over HTTPS
  ;; :ssl-port     - the SSL port to listen on (defaults to 443, implies :ssl?)
  ;; :keystore     - the keystore to use for SSL connections
  ;; :key-password - the password to the keystore
  ;; :truststore   - a truststore to use for SSL connections
  ;; :trust-password - the password to the truststore
  ;; :client-auth  - SSL client certificate authenticate, may be set to :need,
  ;;                 :want or :none (defaults to :none)"

