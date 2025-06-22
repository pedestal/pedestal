(ns io.pedestal.http.jdk-httpserver
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [io.pedestal.connector.test :as test]
            [io.pedestal.http.response :as response]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.log :as log]
            [io.pedestal.service.data :as data]
            [io.pedestal.service.protocols :as p])
  (:import (clojure.lang IPersistentCollection)
           (com.sun.net.httpserver HttpExchange HttpHandler HttpServer HttpsExchange HttpsServer)
           (java.io InputStream OutputStream)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer)
           (java.nio.channels Channels ReadableByteChannel)))

(set! *warn-on-reflection* true)

(defn- ->ring-request
  [^HttpExchange http-exchange]
  (let [request-uri (.getRequestURI http-exchange)
        uri (.getPath request-uri)
        query-string (.getQuery request-uri)
        headers (.getRequestHeaders http-exchange)
        https? (instance? HttpsExchange http-exchange)
        http-context (.getHttpContext http-exchange)
        has-body? (or (.getFirst headers "transfer-encoding")
                    (.getFirst headers "content-length"))
        context-path (.getPath http-context)
        content-type (.getFirst headers "content-type")
        ring-request {:headers        (into {}
                                        (map (fn [[K vs]]
                                               (let [k (string/lower-case K)]
                                                 [k (case k
                                                      "cookie" (string/join "; " vs)
                                                      (string/join ", " vs))])))
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
                      :server-name    (str (or (.getHost request-uri)
                                             (some-> headers (.getFirst "host") (string/split #":([0-9]+)$") first)))
                      :server-port    (-> http-context
                                        .getServer
                                        .getAddress
                                        .getPort)
                      :uri            uri
                      ;; Non-ring spec
                      :context        context-path
                      :path-info      (case context-path
                                        "/" uri
                                        (subs uri (count context-path)))}]
    (cond-> ring-request
      ;; TODO: local or peer?
      ;; TODO: first or last?
      https? (assoc :ssl-client-cert (-> ^HttpsExchange http-exchange
                                       .getSSLSession
                                       .getLocalCertificates #_.getPeerCertificates
                                       first))
      ;; deprecated but still required
      content-type (assoc :content-type content-type)
      has-body? (assoc :body (.getRequestBody http-exchange))
      query-string (assoc :query-string query-string))))

(defprotocol WriteBodyToStream
  (write-body-to-stream [body response output-stream])
  (default-content-type [body]))

(defn- write-ring-response->http-exchange!
  [^HttpExchange http-exchange {:keys [status headers body]
                                :as   ring-response}]
  (let [response-headers (.getResponseHeaders http-exchange)]
    (doseq [[k vs] headers
            v (cond
                (string? vs) [vs]
                (number? vs) [vs]
                :else vs)
            :when (some? v)]
      (.add response-headers k (str v)))
    (if (nil? body)
      (.sendResponseHeaders http-exchange status -1)
      (let [content-length (or (some-> response-headers
                                 (.getFirst "content-length")
                                 parse-long)
                             0)]
        (.sendResponseHeaders http-exchange status content-length)
        (write-body-to-stream body ring-response (.getResponseBody http-exchange))))))

(extend-protocol WriteBodyToStream
  ByteBuffer
  (default-content-type [_] "application/octet-stream")
  (write-body-to-stream [body _ output-stream]
    (with-open [c (Channels/newChannel ^OutputStream output-stream)]
      (.write c body)))
  ReadableByteChannel
  (default-content-type [_] "application/octet-stream")
  (write-body-to-stream [body _ output-stream]
    (with-open [output-stream ^OutputStream output-stream
                input-stream (Channels/newInputStream body)]
      (.transferTo input-stream output-stream)))
  String
  (default-content-type [_] "text/plain")
  (write-body-to-stream [body _ output-stream]
    (with-open [w (io/writer output-stream)]
      (.write w body)))
  InputStream
  (default-content-type [_] "text/plain")
  (write-body-to-stream [body _ output-stream]
    (with-open [body body
                output-stream ^OutputStream output-stream]
      (.transferTo body output-stream)))
  IPersistentCollection
  (default-content-type [_] "application/edn")
  (write-body-to-stream [body response output-stream]
    (write-body-to-stream (pr-str body) response output-stream)))

(def ^:private response-converter
  (interceptor/interceptor
    {:name  ::response-converter
     :leave (fn [{:keys [response] :as context}]
              (if (and (map? response)
                    (number? (:status response)))
                (assoc context :response (let [{:keys [body]} response
                                               content-type (get-in response [:headers "Content-Type"])
                                               default-content-type (some-> body default-content-type)]
                                           (cond-> response
                                             (and (nil? content-type) default-content-type)
                                             (assoc-in [:headers "Content-Type"] default-content-type))))
                (do
                  (log/error :msg "Invalid response"
                    :response response)
                  (dissoc context :response))))}))

(defn create-connector
  [{:keys [port host join? initial-context interceptors]} {:keys [context-path backlog https-configurator]
                                                           :or   {context-path "/"
                                                                  backlog      0}}]
  (let [addr (if (string? host)
               (InetSocketAddress. ^String host (int port))
               (InetSocketAddress. port))
        root-handler (fn [ring-request]
                       (-> initial-context
                         response/terminate-when-response
                         (chain/on-enter-async (fn [_]
                                                 (throw (ex-info "No async channel in request map"
                                                          {:request ring-request}))))
                         (merge (meta ring-request)
                           {:request ring-request})
                         (chain/execute
                           (into [response-converter]
                             interceptors))
                         :response))
        *http-server (delay (if https-configurator
                              (doto (HttpsServer/create addr (int backlog))
                                (.setHttpsConfigurator https-configurator))
                              (HttpServer/create addr backlog)))]
    (reify
      p/PedestalConnector
      (start-connector! [this]

        (let [http-server ^HttpServer @*http-server]
          (try
            (.createContext http-server context-path
              (reify HttpHandler
                (handle [_this http-exchange]
                  (let [ring-response (-> http-exchange
                                        ->ring-request
                                        (with-meta {::http-exchange http-exchange})
                                        root-handler)]
                    (write-ring-response->http-exchange! http-exchange ring-response)))))
            (.start ^HttpServer http-server)
            (when join?
              @(promise))
            this
            (catch Throwable ex
              (.stop http-server 0)
              (throw ex)))))
      (stop-connector! [this]
        (when (realized? *http-server)
          (.stop ^HttpServer @*http-server 0))
        this)
      (test-request [_ ring-request]
        (-> ring-request
          (update :body data/->input-stream)
          root-handler
          (update :body test/coerce-response-body))))))
