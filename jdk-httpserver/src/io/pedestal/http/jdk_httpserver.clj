(ns io.pedestal.http.jdk-httpserver
  (:require [clojure.string :as string]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.service.protocols :as p]
            [ring.core.protocols :as rcp])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer HttpsExchange)
           (java.io OutputStream)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer)
           (java.nio.channels Channels ReadableByteChannel)))

(set! *warn-on-reflection* true)

(defn ->ring-request
  [^HttpExchange http-exchange]
  (let [uri (.getRequestURI http-exchange)
        path-info (.getPath uri)
        query-string (.getQuery uri)
        headers (HttpExchange/.getRequestHeaders http-exchange)]
    (cond-> {:body           (.getRequestBody http-exchange)
             :headers        (into {}
                               (map (fn [[k v]]
                                      ;; TODO: Handle multiple headers?
                                      [k (first v)]))
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
             :scheme         (if (instance? HttpsExchange http-exchange)
                               :https
                               :http)
             :server-name    (str (or (.getHost (HttpExchange/.getRequestURI http-exchange))
                                    (some-> headers (.getFirst "host") (string/split #":") first)))
             :server-port    (-> http-exchange
                               .getHttpContext
                               .getServer
                               .getAddress
                               .getPort)
             #_:ssl-client-cert
             :uri            path-info
             ;; TODO: Handle context/path-info
             :path-info      path-info}
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
    (let [bb (ByteBuffer/allocate #_0x10000 65536)]
      (loop []
        (let [n (ReadableByteChannel/.read body bb)]
          (when (pos-int? n)
            (.write response-body (.array bb) 0 n)
            (.clear bb)
            (recur))))))
  Object
  (-write-body-to-stream [body response response-body]
    (rcp/write-body-to-stream body response response-body)))

(defn create-connector
  [{:keys [port host #_join? initial-context interceptors]} & _]
  (let [;; TODO: context-path
        context-path "/"
        addr (if (string? host)
               (InetSocketAddress. ^String host (int port))
               (InetSocketAddress. port))
        server (HttpServer/create addr 0)]
    (HttpServer/.createContext server context-path
      (reify HttpHandler
        (handle [_this http-exchange]
          (let [{:keys [response]} (-> initial-context
                                     (assoc :request (->ring-request http-exchange))
                                     (chain/execute interceptors))
                {:keys [status headers body]} response
                response-headers (.getResponseHeaders http-exchange)]
            (doseq [[k v] headers]
              (.add response-headers k (str v)))
            (if (nil? body)
              (HttpExchange/.sendResponseHeaders http-exchange status -1)
              (let [content-length (or (some-> response-headers
                                         (.getFirst "Content-Length")
                                         parse-long)
                                     0)]
                (HttpExchange/.sendResponseHeaders http-exchange status content-length)
                (with-open [response-body (.getResponseBody http-exchange)]
                  (-write-body-to-stream body response response-body))))))))
    (reify
      p/PedestalConnector
      (start-connector! [this]
        (HttpServer/.start server)
        this)
      (stop-connector! [this]
        (HttpServer/.stop server 0)
        this))))
