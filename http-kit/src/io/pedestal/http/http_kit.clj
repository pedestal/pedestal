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
  (:require [io.pedestal.http.response :as response]
            [io.pedestal.log :as log]
            [io.pedestal.service.protocols :as p]
            [org.httpkit.server :as hk]
            [io.pedestal.http.http-kit.impl :as impl]
            [io.pedestal.service.test :as test]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.http-kit.response :refer [convert-response-body]]
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
                (when-let [{:keys [response]} context]
                  (hk/send! channel response))
                (hk/close channel))
              context)}))

(def ^:private response-converter
  (interceptor
    {:name  ::response-converter
     :leave (fn [{:keys [response] :as context}]
              (if (response/response? response)
                (update-in context [:response :body] convert-response-body)
                (do
                  (log/error :msg "Invalid response"
                             :response response)
                  (dissoc context :response))))}))

(defn create-connector
  [service-map options]
  (let [{:keys [host port interceptors initial-context join?]} service-map
        options'      (merge default-options
                             options
                             {:ip   host
                              :port port})
        *server       (atom nil)
        root-handler  (fn [request]
                        ;; TODO: something like stylobate,
                        (let [*async-channel (atom nil)
                              interceptors'  (into [(async-responder *async-channel)
                                                    response-converter]
                                                   interceptors)
                              context        (-> initial-context
                                                 (assoc :request request)
                                                 (chain/on-enter-async (fn [_]
                                                                         (reset! *async-channel (or (:async-channel request)
                                                                                                    (throw (ex-info "No async channel in request object"
                                                                                                                    {:request request}))))))
                                                 (chain/execute interceptors'))]
                          ;; When processing goes async, chain/execute will return nil but we'll have captured the Http-Kit async channel.
                          (if-let [async-channel @*async-channel]
                            ;; Returning this to Http-Kit causes it to set things up for an async response to be delivered
                            ;; via hk/send!.
                            {:body async-channel}
                            (or (:response context)
                                (do
                                  (log/error :msg "Execution completed without producing a response"
                                             :request request)
                                  {:status  500
                                   :headers {"Content-Type" "text/plain"}
                                   :body    "Execution completed without producing a response"})))))]
    (reify p/PedestalConnector

      (start-connector! [this]
        (when @*server
          (throw (IllegalStateException. "Http-Kit Connector already started")))

        (reset! *server (hk/run-server root-handler
                                       options'))

        (when join?
          (hk/server-join @*server))

        this)

      (stop-connector! [this]
        (when-let [server @*server]
          (hk/server-stop! server)
          (reset! *server nil))

        this)

      (test-request [_ ring-request]
        (let [*async-response (promise)
              channel         (impl/mock-channel *async-response)
              sync-response   (root-handler (assoc ring-request :async-channel channel))
              response        (if (= (:body sync-response) channel)
                                (or (deref *async-response 1000 nil)
                                    {:status 500
                                     :body   "Async response not produced after 1 second"})
                                sync-response)]
          ;; The response has been converted to support what Http-Kit allows, but we need to further narrow to support
          ;; the test contract (nil or InputStream).
          (update response :body test/convert-response-body))))))

