(ns io.pedestal.connector.jdk-httpserver
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [io.pedestal.connector.jdk-httpserver.test-request :as test-request]
            [io.pedestal.http.response :as response]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.response-mime :as response-mime]
            [io.pedestal.service.protocols :as p])
  (:import (clojure.lang Fn IPersistentCollection)
           (com.sun.net.httpserver HttpExchange HttpHandler HttpsConfigurator HttpServer HttpsExchange HttpsParameters HttpsServer)
           (java.io InputStream OutputStream)
           (java.lang AutoCloseable)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer)
           (java.nio.channels Channels ReadableByteChannel)
           (java.security KeyStore)
           (java.time Duration)
           (javax.net.ssl KeyManagerFactory SSLContext TrustManagerFactory)))

(set! *warn-on-reflection* true)

(defprotocol StreamableResponseBody
  (write-body-to-stream [_ output-stream]
    "Writes the response into a output-stream. It should always close the output-stream once its onde"))

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
    (with-open [os ^OutputStream output-stream]
      (.write (Channels/newChannel os) this)))

  ReadableByteChannel
  (write-body-to-stream [this output-stream]
    (with-open [os ^OutputStream output-stream]
      (.transferTo (Channels/newInputStream this) os)))

  IPersistentCollection
  (write-body-to-stream [this output-stream]
    (write-body-to-stream (str this) output-stream))
  InputStream
  (write-body-to-stream [this output-stream]
    (with-open [os ^OutputStream output-stream]
      (.transferTo this os)))
  nil
  (write-body-to-stream [_ output-stream]
    (.close ^AutoCloseable output-stream)))

(extend (Class/forName "[B")
  StreamableResponseBody
  {:write-body-to-stream (fn [^"[B" this ^OutputStream output-stream]
                           (with-open [os output-stream]
                             (.write os this)))})

(def http-exchange-io
  {:name  ::http-exchange-io
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
                (let [content-length (some-> response-headers
                                       (.getFirst "content-length")
                                       parse-long)
                      response-length (cond
                                        (not (contains? response :body)) -1
                                        (nil? content-length) 0
                                        (zero? content-length) -1
                                        :else content-length)]
                  (.sendResponseHeaders http-exchange status response-length)
                  (when-not (== -1 response-length)
                    (write-body-to-stream body (.getResponseBody http-exchange)))))
              ctx))
   :enter (fn [{:keys [^HttpExchange http-exchange]
                :as   ctx}]
            (let [request-uri (.getRequestURI http-exchange)
                  headers (.getRequestHeaders http-exchange)
                  query (.getQuery request-uri)
                  https? (instance? HttpsExchange http-exchange)
                  http-context (.getHttpContext http-exchange)
                  context (.getPath http-context)
                  uri (.getPath request-uri)
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
                                              :query-string query
                                              :body (.getRequestBody http-exchange)
                                              :context context
                                              :path-info (case context
                                                           "/" uri
                                                           (subs uri (count context) (count uri)))
                                              :uri uri)
                                       (cond->
                                         content-type (assoc :content-type content-type)
                                         https? (assoc :ssl-client-cert (-> ^HttpsExchange http-exchange
                                                                          .getSSLSession
                                                                          .getLocalCertificates #_.getPeerCertificates
                                                                          first))))))))})

(defn https-configurator-factory
  ^HttpsConfigurator
  [{:keys [keystore key-password]}]
  (when keystore
    (let [ks (KeyStore/getInstance "JKS")
          kmf (KeyManagerFactory/getInstance "SunX509")
          tmf (TrustManagerFactory/getInstance "SunX509")
          ssl-context (SSLContext/getInstance "TLS")
          password (.toCharArray (str key-password))]
      (with-open [stream (io/input-stream keystore)]
        (.load ks stream password))
      (.init kmf ks password)
      (.init tmf ks)
      (.init ssl-context (.getKeyManagers kmf) (.getTrustManagers tmf) nil)
      (HttpsConfigurator. ssl-context))))

(defn create-connector
  [{:keys [port host initial-context interceptors]}
   {:keys [context-path backlog ^Duration stop-delay executor]
    :or   {context-path       "/"
           stop-delay         (Duration/ofSeconds 0)
           backlog            0}
    :as   options}]
  (let [https-configurator (https-configurator-factory options)
        ^HttpServer http-server (if https-configurator
                                  (HttpsServer/create)
                                  (HttpServer/create))
        exchange-interceptors (into [(interceptor/interceptor {:name  ::http-exchange-close
                                                               :leave (fn [{:keys [http-exchange]
                                                                            :as   ctx}]
                                                                        (when (instance? AutoCloseable http-exchange)
                                                                          (.close ^AutoCloseable http-exchange))
                                                                        ctx)})
                                     (interceptor/interceptor http-exchange-io)
                                     (interceptor/interceptor response-mime/apply-default-content-type)]

                                interceptors)
        context (response/terminate-when-response initial-context)]
    (reify p/PedestalConnector
      (start-connector! [this]
        (when executor
          (.setExecutor http-server executor))
        (.createContext http-server context-path
          (reify HttpHandler
            (handle [_ http-exchange]
              (chain/execute (assoc context :http-exchange http-exchange)
                exchange-interceptors))))
        (when https-configurator
          (.setHttpsConfigurator ^HttpsServer http-server https-configurator))
        (doto http-server
          (.bind (InetSocketAddress. (str host) (int port)) backlog)
          .start)
        this)
      (stop-connector! [this]
        (.stop http-server (.toSeconds stop-delay))
        this)
      (test-request [_ request]
        (let [http-exchange (test-request/http-exchange options request)]
          (chain/execute (assoc context :http-exchange http-exchange)
            exchange-interceptors)
          @http-exchange)))))
