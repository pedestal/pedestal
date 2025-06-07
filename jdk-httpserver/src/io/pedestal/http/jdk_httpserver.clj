(ns io.pedestal.http.jdk-httpserver
  (:require [clojure.string :as string]
            [io.pedestal.connector.test :as test]
            [io.pedestal.http.http-kit.impl :as impl]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.service.data :as data]
            [io.pedestal.service.protocols :as p]
            [ring.core.protocols :as rcp]
            [ring.util.request :as request])
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
             :uri            uri}
      ;; TODO: https support
      #_#_https? (assoc :ssl-client-cert (-> ^HttpsExchange http-exchange
                                           .getSSLSession
                                           ->X509Certificate))
      query-string (assoc :query-string query-string))))


(defprotocol IWriteBodyToStream
  (-write-body-to-stream [body response response-body])
  (-default-content-type [body]))

(extend-protocol IWriteBodyToStream
  ByteBuffer
  (-default-content-type [_] "application/octet-stream")
  (-write-body-to-stream [body _ ^OutputStream response-body]
    (with-open [c (Channels/newChannel response-body)]
      (.write c body)))
  ReadableByteChannel
  (-default-content-type [_] "application/octet-stream")
  (-write-body-to-stream [body _ ^OutputStream response-body]
    (with-open [input-stream (Channels/newInputStream body)]
      (.transferTo input-stream response-body)))
  String
  (-default-content-type [_]
    "text/plain")
  (-write-body-to-stream [body response response-body]
    (rcp/write-body-to-stream body response response-body))
  IPersistentCollection
  (-default-content-type [_]
    "application/edn")
  (-write-body-to-stream [body response response-body]
    (-write-body-to-stream (pr-str body) response response-body))
  Object
  (-default-content-type [_]
    "application/octet-stream")
  (-write-body-to-stream [body response response-body]
    (rcp/write-body-to-stream body response response-body)))

(defn create-connector
  [{:keys [port host #_join? initial-context interceptors]} {:keys [context-path]
                                                             :or   {context-path "/"}}]
  (let [addr (if (string? host)
               (InetSocketAddress. ^String host (int port))
               (InetSocketAddress. port))
        root-handler (fn [ring-request]
                       (let [request (request/set-context ring-request (if (next context-path)
                                                                         context-path
                                                                         ""))
                             {:keys [headers body]
                              :as   response} (-> initial-context
                                                (assoc :request request)
                                                (chain/execute interceptors)
                                                :response)]
                         (if (get headers "Content-Type")
                           response
                           (assoc response :headers (assoc headers "Content-Type" (-default-content-type body))))))
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
                                            (-write-body-to-stream body response response-body))))))))))]
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
          ;; The response has been converted to support what Http-Kit allows, but we need to further narrow to support
          ;; the test contract (nil or InputStream).
          (update response :body test/coerce-response-body))))))
