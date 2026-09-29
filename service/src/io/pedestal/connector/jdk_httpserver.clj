(ns io.pedestal.connector.jdk-httpserver
  (:require [io.pedestal.connector.jdk-httpserver.test-request :as test-request]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [io.pedestal.response-mime :as response-mime]
            [io.pedestal.http.response :as response]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.service.protocols :as p])
  (:import (clojure.lang Fn IPersistentCollection)
           (com.sun.net.httpserver HttpExchange HttpHandler HttpServer HttpsExchange)
           (java.io InputStream OutputStream)
           (java.lang AutoCloseable)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer)
           (java.nio.channels Channels ReadableByteChannel)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(defprotocol StreamableResponseBody
  (write-body-to-stream [_ output-stream]))

(extend-protocol StreamableResponseBody
  Fn
  (write-body-to-stream [this output-stream]
    (with-open [os ^AutoCloseable output-stream]
      (this os)))
  String
  (write-body-to-stream [this output-stream]
    (with-open [w (io/writer output-stream)]
      (.append w this)))
  ByteBuffer
  (write-body-to-stream [this output-stream]
    (.write (Channels/newChannel ^OutputStream output-stream) this))

  ReadableByteChannel
  (write-body-to-stream [this output-stream]
    (.transferTo (Channels/newInputStream this) output-stream))

  IPersistentCollection
  (write-body-to-stream [this output-stream]
    (write-body-to-stream (str this) output-stream))
  byte/1
  (write-body-to-stream [this output-stream]
    (with-open [os ^AutoCloseable output-stream]
      (OutputStream/.write os ^byte/1 this)))
  InputStream
  (write-body-to-stream [this output-stream]
    (with-open [os ^AutoCloseable output-stream]
      (.transferTo this os)))
  nil
  (write-body-to-stream [_ output-stream]
    (.close ^AutoCloseable output-stream)))

(def http-exchange-io
  {:name  :http-exchange-io
   :leave (fn [{:keys [^HttpExchange http-exchange response]
                :as   ctx}]
            (let [{:keys [status body headers]} response]
              (let [response-headers (.getResponseHeaders http-exchange)]
                (doseq [[k vs] headers
                        v (cond
                            (string? vs) [vs]
                            (number? vs) [vs]
                            :else vs)
                        :when (some? v)]
                  (.add response-headers k (str v)))
                (if-not (contains? response :body)
                  (.sendResponseHeaders http-exchange status -1)
                  (let [content-length (or (some-> response-headers
                                             (.getFirst "content-length")
                                             parse-long)
                                         0)]
                    (.sendResponseHeaders http-exchange status content-length)
                    (write-body-to-stream body (.getResponseBody http-exchange)))))
              ctx))
   :enter (fn [{:keys [^HttpExchange http-exchange]
                :as   ctx}]
            (let [request-uri (.getRequestURI http-exchange)
                  headers (.getRequestHeaders http-exchange)
                  query (.getQuery request-uri)
                  https? (instance? HttpsExchange http-exchange)
                  path (.getPath request-uri)
                  remote-addr (some-> http-exchange
                                .getRemoteAddress
                                .getAddress
                                .getHostAddress)
                  content-type (.getFirst headers "content-type")]
              (update ctx :request (fn [ring-request]
                                     (-> ring-request
                                       (assoc :headers (into {}
                                                         (map (fn [[K vs]]
                                                                (let [k (string/lower-case K)]
                                                                  [k (case k
                                                                       "cookie" (string/join ";" vs)
                                                                       (string/join "," vs))])))
                                                         headers)
                                              :protocol (.getProtocol http-exchange)
                                              :remote-addr remote-addr
                                              :request-method (-> http-exchange
                                                                .getRequestMethod
                                                                string/lower-case
                                                                keyword)
                                              :scheme (if https?
                                                        :https
                                                        :http)
                                              :server-name (str (or (.getHost request-uri)
                                                                  (some-> headers (.getFirst "host") (string/split #":([0-9]+)$") first)))
                                              :server-port (.getPort request-uri)
                                              :path-info path
                                              :query-string query
                                              :body (.getRequestBody http-exchange)
                                              :uri path)
                                       (cond->
                                         content-type (assoc :content-type content-type)
                                         https? (assoc :ssl-client-cert (-> ^HttpsExchange http-exchange
                                                                          .getSSLSession
                                                                          .getLocalCertificates #_.getPeerCertificates
                                                                          first))))))))})

(defn create-connector
  [{:keys [port host initial-context interceptors]}
   {:keys [context-path backlog ^Duration stop-delay]
    :or   {context-path "/"
           stop-delay   (Duration/ofSeconds 0)
           backlog      0}}]
  (let [http-server (HttpServer/create)
        addr (InetSocketAddress. (str host) (int port))
        exchange-interceptors (into [(interceptor/interceptor {:name  :http-exchange-close
                                                               :leave (fn [{:keys [http-exchange]
                                                                            :as   ctx}]
                                                                        (when (instance? AutoCloseable http-exchange)
                                                                          (AutoCloseable/.close http-exchange))
                                                                        ctx)})
                                     (interceptor/interceptor http-exchange-io)
                                     (interceptor/interceptor response-mime/apply-default-content-type)]

                                interceptors)
        context (response/terminate-when-response initial-context)]
    (.createContext http-server context-path
      (reify HttpHandler
        (handle [_ http-exchange]
          (chain/execute (assoc context :http-exchange http-exchange)
            exchange-interceptors))))
    (reify p/PedestalConnector
      (start-connector! [this]
        (.bind http-server addr backlog)
        (.start http-server)
        this)
      (stop-connector! [this]
        (.stop http-server (.toSeconds stop-delay))
        this)
      (test-request [_ request]
        (let [http-exchange (test-request/http-exchange request)]
          (chain/execute (assoc context :http-exchange http-exchange)
            exchange-interceptors)
          @http-exchange)))))
