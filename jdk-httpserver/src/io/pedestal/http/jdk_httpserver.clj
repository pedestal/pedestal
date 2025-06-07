(ns io.pedestal.http.jdk-httpserver
  (:require [clojure.string :as string]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.service.protocols :as p]
            [ring.core.protocols :as rcp]
            [ring.util.request :as request])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer HttpsExchange)
           (java.io OutputStream)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer)
           (java.nio.channels Channels ReadableByteChannel)))

(set! *warn-on-reflection* true)

(defn ->ring-request
  [^HttpExchange http-exchange]
  (let [request-uri (.getRequestURI http-exchange)
        context-path (.getPath (.getHttpContext http-exchange))
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
             :uri            uri
             :context-path   context-path}
      ;; TODO: https support
      #_#_https? (assoc :ssl-client-cert (-> ^HttpsExchange http-exchange
                                           .getSSLSession
                                           ->X509Certificate))
      :always (request/set-context context-path)
      query-string (assoc :query-string query-string))))


(defprotocol IWriteBodyToStream
  (-write-body-to-stream [body response response-body]))

(extend-protocol IWriteBodyToStream
  ByteBuffer
  (-write-body-to-stream [body _ ^OutputStream response-body]
    (with-open [c (Channels/newChannel response-body)]
      (.write c body)))
  ReadableByteChannel
  (-write-body-to-stream [body _ ^OutputStream response-body]
    (with-open [input-stream (Channels/newInputStream body)]
      (.transferTo input-stream response-body)))
  Object
  (-write-body-to-stream [body response response-body]
    (rcp/write-body-to-stream body response response-body)))

(defn create-connector
  [{:keys [port host #_join? initial-context interceptors]} {:keys [context-path]
                                                             :or   {context-path "/"}}]
  (let [addr (if (string? host)
               (InetSocketAddress. ^String host (int port))
               (InetSocketAddress. port))
        server (HttpServer/create addr 0)]
    (.createContext server context-path
      (reify HttpHandler
        (handle [_this http-exchange]
          (let [{:keys [response]} (-> initial-context
                                     (assoc :request (->ring-request http-exchange))
                                     (chain/execute interceptors))
                {:keys [status headers body]} response
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
                  (-write-body-to-stream body response response-body))))))))
    (reify
      p/PedestalConnector
      (start-connector! [this]
        (.start server)
        this)
      (stop-connector! [this]
        (.stop server 0)
        this))))
