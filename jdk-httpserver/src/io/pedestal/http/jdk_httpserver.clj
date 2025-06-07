(ns io.pedestal.http.jdk-httpserver
  (:require [clojure.string :as string]
            [io.pedestal.connector.test :as test]
            [io.pedestal.http.http-kit.impl :as impl]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.log :as log]
            [io.pedestal.service.data :as data]
            [io.pedestal.service.protocols :as p]
            [ring.core.protocols :as rcp]
            [ring.util.request :as request]
            [ring.util.response :as response])
  (:import (clojure.lang IPersistentCollection)
           (com.sun.net.httpserver HttpExchange HttpHandler HttpServer HttpsExchange)
           (java.io OutputStream)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer)
           (java.nio.channels Channels ReadableByteChannel)))

(set! *warn-on-reflection* true)

(defn ->ring-request
  [^HttpExchange http-exchange]
  (let [request-uri (.getRequestURI http-exchange)
        uri (.getPath request-uri)
        query-string (.getQuery request-uri)
        headers (.getRequestHeaders http-exchange)
        https? (instance? HttpsExchange http-exchange)]
    (cond-> {:body           (.getRequestBody http-exchange)
             :headers        (into {}
                               (map (fn [[k v]]
                                      ;; Follows jetty impl
                                      [k (string/join "," v)]))
                               headers)
             :protocol       (.getProtocol http-exchange)
             :remote-addr    (-> http-exchange
                               .getRemoteAddress
                               .getAddress
                               .getHostAddress)
             :request-method (-> http-exchange
                               .getRequestMethod
                               string/lower-case
                               keyword)
             :scheme         (if https?
                               :https
                               :http)
             :server-name    (str (or (.getHost (.getRequestURI http-exchange))
                                    (some-> headers (.getFirst "host") (string/split #":") first)))
             :server-port    (-> http-exchange
                               .getHttpContext
                               .getServer
                               .getAddress
                               .getPort)
             :uri            uri}
      ;; TODO: https support
      #_#_https? (assoc :ssl-client-cert (-> ^HttpsExchange http-exchange
                                           .getSSLSession
                                           ->X509Certificate))
      query-string (assoc :query-string query-string))))


(defprotocol IWriteBodyToStream
  (-as-ring-body [body])
  (-default-content-type [body]))

(extend-protocol IWriteBodyToStream
  ByteBuffer
  (-default-content-type [_] "application/octet-stream")
  (-as-ring-body [body]
    (reify rcp/StreamableResponseBody
      (write-body-to-stream [_ _ output-stream]
        (with-open [c (Channels/newChannel ^OutputStream output-stream)]
          (.write c body)))))
  ReadableByteChannel
  (-default-content-type [_] "application/octet-stream")
  (-as-ring-body [body]
    (reify rcp/StreamableResponseBody
      (write-body-to-stream [_ _ output-stream]
        (with-open [input-stream (Channels/newInputStream body)]
          (.transferTo input-stream ^OutputStream output-stream)))))
  String
  (-default-content-type [_] "text/plain")
  (-as-ring-body [body] body)
  IPersistentCollection
  (-default-content-type [_] "application/edn")
  (-as-ring-body [body] (pr-str body))
  Object
  (-default-content-type [_] "application/octet-stream")
  (-as-ring-body [body] body))

(def response-converter
  (interceptor/interceptor
    {:name  ::response-converter
     :leave (fn [{:keys [response] :as context}]
              (if (response/response? response)
                (assoc context :response (let [{:keys [body]} response
                                               content-type (get-in response [:headers "Content-Type"])
                                               default-content-type (some-> body -default-content-type)]
                                           (cond-> response
                                             (some? body) (assoc :body (-as-ring-body body))
                                             (and (nil? content-type) default-content-type)
                                             (assoc-in [:headers "Content-Type"] default-content-type))))
                (do
                  (log/error :msg "Invalid response"
                    :response response)
                  (dissoc context :response))))}))

(defn create-connector
  [{:keys [port host #_join? initial-context interceptors]} {:keys [context-path]
                                                             :or   {context-path "/"}}]
  (let [addr (if (string? host)
               (InetSocketAddress. ^String host (int port))
               (InetSocketAddress. port))
        root-handler (fn [ring-request]
                       (let [request (request/set-context ring-request (if (next context-path)
                                                                         context-path
                                                                         ""))]
                         (-> initial-context
                           (assoc :request request)
                           (chain/execute
                             (into [response-converter]
                               interceptors))
                           (doto (->> (def _aaaaa)))
                           :response)))
        *http-server (delay (doto (HttpServer/create addr 0)
                              (.createContext context-path
                                (reify HttpHandler
                                  (handle [_this http-exchange]
                                    (let [{:keys [status headers body]
                                           :as   response} (root-handler (->ring-request http-exchange))
                                          response-headers (.getResponseHeaders http-exchange)]
                                      (doseq [[k v] headers]
                                        ;; TODO: Handle collections on value?!
                                        (.add response-headers k (str v)))
                                      (if (nil? body)
                                        (.sendResponseHeaders http-exchange status -1)
                                        (let [content-length (or (some-> response-headers
                                                                   (.getFirst "content-length")
                                                                   parse-long)
                                                               0)]
                                          (.sendResponseHeaders http-exchange status content-length)
                                          (with-open [response-body (.getResponseBody http-exchange)]
                                            (rcp/write-body-to-stream body response response-body))))))))))]
    (reify
      p/PedestalConnector
      (start-connector! [this]
        (.start ^HttpServer @*http-server)
        this)
      (stop-connector! [this]
        (when (realized? *http-server)
          (.stop ^HttpServer @*http-server 0))
        this)
      (test-request [_ ring-request]
        (let [*async-response (promise)
              channel (impl/mock-channel *async-response)
              request (-> ring-request
                        (update :body data/->input-stream)
                        (assoc :async-channel channel))
              sync-response (root-handler request)
              response (if (= (:body sync-response) channel)
                         (or (deref *async-response 1000 nil)
                           {:status 500
                            :body   "Async response not produced after 1 second"})
                         sync-response)]
          (update response :body test/coerce-response-body))))))
