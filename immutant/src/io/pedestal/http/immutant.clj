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
    :keystore
    :key-password
    :truststore
    :trust-password

   TODO: :client-auth"
  ([servlet]
     (server servlet {}))
  ([servlet options]
     (let [server (-> (merge options (:container-options options))
                    undertow/options
                    (select-keys [:path :virtual-host :configuration])
                    (assoc :auto-start false)
                    (->> (web/run servlet))
                    (web/server))]
       {:server   server
        :start-fn #(start server)
        :stop-fn  #(stop server)})))
