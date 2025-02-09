; Copyright 2025 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.http-kit
  "Support for [Http-Kit](https://github.com/http-kit/http-kit) as a network connector.

  HttpKit provides features similar to the Servlet API, including WebSockets, but does not
  implement any of the underlying interfaces."
  (:require [io.pedestal.http :as http]
            [org.httpkit.server :as hk-server]
            [io.pedestal.http.impl.servlet-interceptor :as si]
            [io.pedestal.interceptor.chain :as chain]))


(defn- channelize
  [request]
  (hk-server/as-channel request nil))

(defn- create-provider
  [service-map]
  (let [{::http/keys [interceptors
                      initial-context
                      container-options]} service-map
        {:keys [exception-analyzer]
         :or   {exception-analyzer si/default-exception-analyzer}} container-options
        context (-> initial-context
                    si/terminate-when-response
                    (chain/enqueue [])
                    (chain/enqueue interceptors))])

  )

(defn chain-provider
  "Implements the equivalent of the [[http-interceptor-service-fn]], but for HttpKit.

  You should as :chain-provider io.pedestal.http.http-kit/chain-provider to your service map."
  [service-map]
  (let [chain-provider (create-provider service-map)]
    (assoc service-map ::service-fn chain-provider)))
