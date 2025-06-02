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
  {:added "0.8.0"}
  (:require [io.pedestal.http.response :as response]
            [io.pedestal.log :as log]
            [io.pedestal.service.data :as data :refer [convert]]
            [io.pedestal.service.protocols :as p]
            [io.pedestal.service.websocket :as ws]
            [org.httpkit.server :as hk]
            [io.pedestal.http.http-kit.impl :as impl]
            [io.pedestal.connector.test :as test]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.http-kit.response :refer [convert-response-body]]
            [io.pedestal.interceptor.chain :as chain])
  (:import (org.httpkit.server AsyncChannel)))

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

(defn- prepare-response
  [request response]
  (let [{:keys [body]} response
        content-type (get-in response [:headers "Content-Type"])
        [default-content-type body'] (convert-response-body body request)]
    (-> response
        (assoc :body body')
        (cond->
          (and (nil? content-type) default-content-type)
          (assoc-in [:headers "Content-Type"] default-content-type)))))

(def ^:private response-converter
  (interceptor
    {:name  ::response-converter
     :leave (fn [{:keys [request response] :as context}]
              (if (response/response? response)
                (assoc context :response (prepare-response request response))
                (do
                  (log/error :msg "Invalid response"
                             :response response)
                  (dissoc context :response))))}))

(defn create-connector
  [service-map options]
  (let [{:keys [host port interceptors initial-context join?]} service-map
        options'     (merge default-options
                            options
                            {:ip   host
                             :port port})
        *server      (atom nil)
        root-handler (fn [request]
                       (let [{:keys [uri async-channel]} request
                             request'       (assoc request :path-info uri)
                             *async-channel (atom nil)
                             interceptors'  (into [(async-responder *async-channel)
                                                   response-converter]
                                                  interceptors)
                             context        (-> initial-context
                                                (assoc :request request'
                                                       :websocket-channel-source async-channel)
                                                (chain/on-enter-async (fn [_]
                                                                        (reset! *async-channel (or async-channel
                                                                                                   (throw (ex-info "No async channel in request map"
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
              request         (-> ring-request
                                  (update :body data/->input-stream)
                                  (assoc :async-channel channel))
              sync-response   (root-handler request)
              response        (if (= (:body sync-response) channel)
                                (or (deref *async-response 1000 nil)
                                    {:status 500
                                     :body   "Async response not produced after 1 second"})
                                sync-response)]
          ;; The response has been converted to support what Http-Kit allows, but we need to further narrow to support
          ;; the test contract (nil or InputStream).
          (update response :body test/coerce-response-body))))))

(defn- initialize-websocket*
  "Knit together Pedestal's lifecycle with Http-Kit's."
  [ch context ws-opts]
  (let [{:keys [request]} context
        {:keys [on-close]} ws-opts
        ;; Http-Kit always wants to setup on-receive before the open, so we do that but allow
        ;; for the actual callback to be provided after the open callback.
        *on-text    (atom nil)
        *on-binary  (atom nil)
        *proc       (atom nil)
        ws-channel  (reify ws/WebSocketChannel

                      (on-text [_ callback]
                        (when @*on-text
                          (throw (ex-info "on-text callback has already been set"
                                          {:ch ch})))

                        (reset! *on-text callback)

                        nil)

                      (on-binary [_ format callback]
                        (when @*on-binary
                          (throw (ex-info "on-binary callback has already been set"
                                          {:ch ch})))

                        (reset! *on-binary (fn [ws-channel proc raw-binary]
                                             (callback ws-channel proc (convert format raw-binary))))

                        nil)

                      (send-text! [_ string]
                        (hk/send! ch string))

                      (send-binary! [_ data]
                        (let [data' (if (bytes? data)
                                      data
                                      (convert :input-stream data))]
                          (hk/send! ch data')))

                      (close! [_]
                        (hk/close ch)

                        nil))
        on-receive  (fn [_ message]
                      (let [*callback (if (string? message) *on-text *on-binary)
                            f         @*callback]
                        (when f
                          (f ws-channel @*proc message))))
        ch-opts     (cond-> {:on-receive on-receive
                             :on-open    (fn [_]
                                           (when-let [f (:on-open ws-opts)]
                                             (reset! *proc (f ws-channel request)))

                                           (when-let [f (:on-text ws-opts)]
                                             (ws/on-text ws-channel f))

                                           (when-let [f (:on-binary ws-opts)]
                                             (ws/on-binary ws-channel :byte-buffer f)))}
                      on-close (assoc :on-close
                                      (fn [_ status-code]
                                        (on-close ws-channel @*proc status-code))))
        hk-response (hk/as-channel request ch-opts)]
    ;; The :status is needed to keep the interceptor terminator checker from complaining.
    (assoc context :response (assoc hk-response :status 200))))

(extend-type AsyncChannel

  ws/InitializeWebSocket

  (initialize-websocket [ch context ws-opts]
    (initialize-websocket* ch context ws-opts)))
