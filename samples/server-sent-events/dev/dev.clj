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

;; dev mode in repl (can get prod mode by passing prod options to dev-init
(ns dev
  (:require [io.pedestal.service.http :as bootstrap]
            [server-sent-events.service :as service]
            [server-sent-events.server :as server]))

(def service (-> service/service
                 (merge  {:env :dev
                          ::bootstrap/join? false
                          ::bootstrap/routes #(deref #'service/routes)})
                 (bootstrap/default-interceptors)
                 (bootstrap/dev-interceptors)))

(defn start
  [& [opts]]
  (server/create-server (merge service opts))
  (bootstrap/start server/service-instance))

(defn stop
  []
  (bootstrap/stop server/service-instance))

(defn restart
  []
  (stop)
  (start))

