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
  (:require [immutant.web :as web]
            [immutant.web.internal.wunderboss :as internal])
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
  ([servlet] (server servlet {}))
  ([servlet opts]
     (let [options (-> opts
                     (select-keys [:path :static-dir :port :host :virtual-host :configuration])
                     (assoc :auto-start false))
           server (internal/server options)]
       (web/run servlet options)
       {:server   server
        :start-fn #(start server)
        :stop-fn  #(stop server)})))
