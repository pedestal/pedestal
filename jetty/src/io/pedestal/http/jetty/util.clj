; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.jetty.util
  (:import (java.util EnumSet)
           (javax.servlet Servlet Filter DispatcherType)
           (org.eclipse.jetty.servlet ServletContextHandler FilterHolder)
           (org.eclipse.jetty.server HttpConfiguration
                                     SecureRequestCustomizer
                                     ConnectionFactory
                                     HttpConnectionFactory)
           (org.eclipse.jetty.alpn.server ALPNServerConnectionFactory)
           (org.eclipse.jetty.spdy.server.http HTTPSPDYServerConnectionFactory
                                               PushStrategy$None)
           (org.eclipse.jetty.spdy.api SPDY)))

(def dispatch-types {:forward DispatcherType/FORWARD
                     :include DispatcherType/INCLUDE
                     :request DispatcherType/REQUEST
                     :async DispatcherType/ASYNC
                     :error DispatcherType/ERROR})

(defn ^EnumSet dispatcher-set
  "Return a dispatch EnumSet given one of:
   - an EnumSet (no-op)
   - servlet DispatcherType
   - a keyword representation of DispatcherType (see `dispatch-types`)
   - `:all` which generates an EnumSet of all DispatcherTypes"
  [dispatches]
  (cond
    (instance? EnumSet dispatches) dispatches
    (instance? DispatcherType dispatches) (EnumSet/of dispatches)
    (= :all dispatches) (EnumSet/allOf DispatcherType)
    (dispatch-types dispatches) (EnumSet/of (dispatch-types dispatches))
    :else (throw
            (ex-info
              (str "You can only dispatch on an established dispatch type,
                   EnumSet thereof, or shorthand keyword.
                   Unaccepted: " dispatches)
              {:accepted-keywords (keys dispatch-types)
               :attempted dispatches}))))

(defn ^FilterHolder filter-holder [servlet-filter init-params]
  (let [holder (FilterHolder. servlet-filter)]
    (doseq [[k v] init-params]
      (.setInitParameter holder k v))
    holder))

(defn ^ServletContextHandler add-servlet-filter
  "Add a ServletFilter to a ServletContextHandler,
  given the context and a map that contains:
    :filter - A FilterHolder, Filter class, or a String of a Filter class
  and optionally contains:
    :path - The pathSpec string that applies to the filter; defaults to '/*'
    :dispatches - A keyword signaling the defaults to :request"
  [^ServletContextHandler context filter-opts]
  (let [{servlet-filter :filter
         path :path
         dispatches :dispatches
         :or {path "/*"
              dispatches :request}} filter-opts
        dispatch-set (dispatcher-set dispatches)]
    ;; Try out best to avoid the Reflection hit
    (cond
      (class? servlet-filter) (.addFilter context ^Class servlet-filter ^String path ^EnumSet dispatch-set)
      (instance? FilterHolder servlet-filter) (.addFilter context ^FilterHolder servlet-filter ^String path ^EnumSet dispatch-set)
      (string? servlet-filter) (.addFilter context ^String servlet-filter ^String path ^EnumSet dispatch-set)
      :else (.addFilter context servlet-filter path dispatch-set))
    context))

(defn ^ServletContextHandler add-server-filters
  [context & more-filter-opts]
  (doseq [filter-opts more-filter-opts]
    (add-servlet-filter context filter-opts)))

;; =========
;; Connection Factories
;; --------------------------
;;
;; These Factories may be added to your Jetty server instance to enhance
;; it's functionality.  ALPN, SPDY, and more can all be added as connection
;; factories.  Some of these factories assume other server settings (like SSL).

(defn ^ALPNServerConnectionFactory alpn-connection-factory
  "Creates a new ALPN Connection Factory to integrate on top of SSL,
  from a map of ALPN options - {:protocols [...] :default-protocol \"\"} within
  the container-options
  Note: SSL must be active to use this factory"
  [options http-conf]
  (let [{:keys [alpn]} (:container-options options)
        {:keys [protocols default-protocol]
         :or {protocols ["http/1.1"]
              default-protocol "http/1.1"}} alpn]
    (.addCustomizer http-conf (SecureRequestCustomizer.))
    (doto (ALPNServerConnectionFactory. (into-array String protocols))
      (.setDefaultProtocol default-protocol))))

(defn ^HTTPSPDYServerConnectionFactory spdy-connection-factory
  "Creates a new HTTP SPDY Connection Factory on top of SSL and ALPN,
  from a map of SPDY options - {:version int :push-strategy PushStrategy} within
  the container options
  Note: SSL and ALPN must be active to use this factory"
  [options http-conf]
  (let [{:keys [spdy]} (:container-options options)
        {:keys [version push-strategy]
         :or {version SPDY/V3
              push-strategy (PushStrategy$None.)}} spdy]
    (HTTPSPDYServerConnectionFactory. version http-conf push-strategy)))

(defn ^HttpConnectionFactory http-connection-factory
  [options http-conf]
  (HttpConnectionFactory. http-conf))

