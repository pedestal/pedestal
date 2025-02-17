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

  Http-Kit provides features similar to the Servlet API, including WebSockets, but does not
  implement any of the underlying Servlet API or WebSocket interfaces."
  (:require [io.pedestal.log :as log]
            [io.pedestal.service.protocols :as p]
            [org.httpkit.server :as hk]
            [org.httpkit.server :as hk-server]
            [io.pedestal.service.test :as test]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.interceptor.chain :as chain]))

(def ^:private default-options
  {:server-header        "Pedestal/http-kit"
   :error-logger         (fn [message error]
                           (log/error :message message :ex error))
   :warn-logger          (fn [message error]
                           (log/warn :message message :ex error))
   :legacy-return-value? false})

(defn- async-responder
  [*channel]
  (interceptor
    {:name  ::async-responder
     :leave (fn [context]
              (when-let [channel @*channel]
                ;; TODO: convert response body as needed
                (hk/send! channel (:response context) true))
              context)}))

(defn create-connector
  [service-map options]
  (let [{:keys [host port interceptors initial-context join?]} service-map
        options'      (merge default-options
                             options
                             {:ip   host
                              :port port})
        *server       (atom nil)
        *join-promise (promise)
        root-handler  (fn [request]
                        ;; TODO: something like stylobate,
                        ;; TODO: handle response body not supported by http-kit
                        (let [*async-channel (atom nil)
                              interceptors'  (into [(async-responder *async-channel)] interceptors)
                              context        (-> initial-context
                                                 (assoc :request request)
                                                 (chain/on-enter-async (fn [_]
                                                                         (reset! *async-channel (or (:async-channel request)
                                                                                                    (throw (ex-info "No async channel in request object"
                                                                                                                    {:request request}))))))
                                                 (chain/execute interceptors'))]
                          ;; When processing goes async, chain/execute will return nil but we'll have captured the Http-Kit async channel.
                          (if-let [async-channel @*async-channel]
                            {:body async-channel}
                            (:response context))))]
    (reify p/PedestalConnector

      (start-connector! [this]
        (when @*server
          (throw (ex-info "Connector already started")))

        (reset! *server (hk-server/run-server root-handler
                                              options'))

        ;; TODO: Broken because doesn't work right when restarting a stopped
        ;; connection.
        (when join?
          @*join-promise)

        this)

      (stop-connector! [this]
        (when-let [server @*server]
          (hk-server/server-stop! server)
          (reset! *server nil)
          (deliver *join-promise nil))

        this)

      (test-request [_ ring-request]
        (let [response (root-handler ring-request)]
          (update response :body test/convert-response-body))))))

