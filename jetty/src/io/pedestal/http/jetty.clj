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
  (:import (org.eclipse.jetty.server Server Request)
           (org.eclipse.jetty.server.handler AbstractHandler)
           (org.eclipse.jetty.server.nio SelectChannelConnector)
           (org.eclipse.jetty.server.ssl SslSelectChannelConnector)
           (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (org.eclipse.jetty.util.thread QueuedThreadPool)
           (org.eclipse.jetty.util.ssl SslContextFactory)
           (javax.servlet Servlet)
           (java.security KeyStore)
           (javax.servlet.http HttpServletRequest HttpServletResponse)))

;; This is based on code from ring.adapter.jetty
;; repo
;; contributors

(defn- ssl-context-factory
  "Creates a new SslContextFactory instance from a map of options."
  [{:keys [^KeyStore keystore key-password ^KeyStore truststore ^String trust-password client-auth]}]
  (let [context (SslContextFactory.)]
    (if (string? keystore)
      (.setKeyStorePath context keystore)
      (.setKeyStore context keystore))
    (.setKeyStorePassword context key-password)
    (when truststore
      (.setTrustStore context truststore))
    (when trust-password
      (.setTrustStorePassword context trust-password))
    (case client-auth
      :need (.setNeedClientAuth context true)
      :want (.setWantClientAuth context true)
      nil)
    context))

(defn- ssl-connector
  "Creates a SslSelectChannelConnector instance."
  [{host :host
    {ssl-port :ssl-port :or {ssl-port 443}
     :as jetty-options} :jetty-options}]
  (doto (SslSelectChannelConnector. (ssl-context-factory jetty-options))
    (.setPort ssl-port)
    (.setHost host)))

(defn- create-server
  "Construct a Jetty Server instance."
  [servlet
   {host :host
    port :port
    {:keys [ssl? ssl-port configurator max-threads daemon?]
     :or {configurator identity
          max-threads 50}} :jetty-options
    :as options}]
  (let [connector (doto (SelectChannelConnector.)
                    (.setPort port)
                    (.setHost host))
        thread-pool (QueuedThreadPool. ^Integer max-threads)
        server (doto (Server.)
                 (.addConnector connector)
                 (.setSendDateHeader true)
                 (.setThreadPool thread-pool))
        context (doto (ServletContextHandler. server "/")
                  (.addServlet (ServletHolder. ^javax.servlet.Servlet servlet) "/*"))]
    (when daemon?
      (.setDaemon thread-pool true))
    (when (or ssl? ssl-port)
      (.addConnector server (ssl-connector options)))
    (configurator server)))

(defn start
  [^Server server
   {:keys [join?] :or {join? true} :as options}]
  (.start server)
  (when join? (.join server)))

(defn stop [^Server server]
  (.stop server))

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
  ;; :configurator - a function called with the Jetty Server instance
  ;; :ssl?         - allow connections over HTTPS
  ;; :ssl-port     - the SSL port to listen on (defaults to 443, implies :ssl?)
  ;; :keystore     - the keystore to use for SSL connections
  ;; :key-password - the password to the keystore
  ;; :truststore   - a truststore to use for SSL connections
  ;; :trust-password - the password to the truststore
  ;; :client-auth  - SSL client certificate authenticate, may be set to :need,
  ;;                 :want or :none (defaults to :none)"
