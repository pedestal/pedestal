; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.immutant
  (:require io.pedestal.http.immutant.container
            [immutant.web :as web]
            [immutant.web.undertow :as undertow])
  (:import org.projectodd.wunderboss.web.Web))

(defn start
  [^Web server]
  (.start server)
  server)

(defn stop
  [^Web server]
  (.stop server)
  server)

(defn ctx-config-hookup
  "Apply a context configurator to result of `web/run`, before `web/server` is called.
  This is currently only used for connecting Immutant WebSocket support."
  [request service-map]
  (if-let [context-configurator (get-in service-map
                                        [:io.pedestal.http/container-options
                                         :context-configurator])]
    (context-configurator request)
    request))

(defn server
  "Standard options
    :port [8080]
    :host [localhost]

   Undertow tuning options (defaults depend on available resources)
    :io-threads
    :worker-threads
    :buffer-size
    :buffers-per-region
    :direct-buffers?

   SSL-related options
    :ssl-port
    :ssl-context
    :key-managers
    :trust-managers
    :keystore (either file path or KeyStore)
    :key-password
    :truststore (either file path or KeyStore)
    :trust-password
    :client-auth (either :want or :need)"
  ([service-map]
     (server service-map {}))
  ([service-map options]
   (let [server (-> (merge options (:container-options options))
                    (select-keys [:trust-managers :key-managers :keystore :buffer-size :buffers-per-region :worker-threads
                                  :port :host :ssl-context :io-threads :client-auth :direct-buffers? :trust-password :key-password
                                  :truststore :configuration :ssl-port :ajp-port])
                    undertow/options
                    (select-keys [:path :virtual-host :configuration])
                    (assoc :auto-start false)
                    (->> (web/run (:io.pedestal.http/servlet service-map)))
                    (ctx-config-hookup service-map) ;; WebSocket Endpoint Hookup
                    web/server)]
       {:server   server
        :start-fn #(start server)
        :stop-fn  #(stop server)})))
