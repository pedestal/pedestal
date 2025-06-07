(ns io.pedestal.http.jdk-httpserver
  (:require [clojure.core.async :as async]
            [clojure.string :as string]
            [io.pedestal.http :as http]
            [io.pedestal.http.container :as container]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.log :as log]
            [io.pedestal.service.protocols :as p]
            [ring.core.protocols :as rcp])
  (:import (com.sun.net.httpserver Headers HttpExchange HttpHandler HttpServer HttpsExchange)
           (jakarta.servlet AsyncContext Servlet ServletInputStream ServletOutputStream)
           (jakarta.servlet.http HttpServletRequest HttpServletResponse)
           (java.io IOException InputStream OutputStream)
           (java.lang AutoCloseable)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer)
           (java.nio.channels Channels ReadableByteChannel)
           (java.util Collections Map)))

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
             #_:remote-addr
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


#_org.eclipse.jetty.server.Request
(defn http-exchange->http-servlet-request
  [http-exchange *async-context]
  (reify HttpServletRequest
    (getProtocol [_this] (HttpExchange/.getProtocol http-exchange))
    (getMethod [_this] (HttpExchange/.getRequestMethod http-exchange))
    (getContentLengthLong [this] (or (some-> this (.getHeader "Content-Length") parse-long)
                                   -1))
    (getContentType [this] (.getHeader this "Content-Type"))
    (setAttribute [_this name o]
      (HttpExchange/.setAttribute http-exchange name o))
    (getAttribute [_this name]
      (HttpExchange/.getAttribute http-exchange name))
    (getCharacterEncoding [_this] nil)
    (getInputStream [_this]
      (let [*in (delay (HttpExchange/.getRequestBody http-exchange))]
        (proxy [ServletInputStream] []
          (read
            ([b]
             (InputStream/.read @*in b))
            ([b off len]
             (InputStream/.read @*in b off len)))
          (close []
            (AutoCloseable/.close @*in)))))
    (getAsyncContext [_this]
      (reify AsyncContext
        ;; TODO
        (setTimeout [_this _timeout])
        (complete [_this]
          (AutoCloseable/.close http-exchange))))
    (getRequestURI [_this] (.getPath (HttpExchange/.getRequestURI http-exchange)))
    (getQueryString [_this] (.getQuery (HttpExchange/.getRequestURI http-exchange)))
    (getScheme [_this]
      (if (instance? HttpsExchange http-exchange)
        "https"
        "http"))
    (getServerName [this]
      (str (or (.getHost (HttpExchange/.getRequestURI http-exchange))
             (some-> this (.getHeader "Host") (string/split #":") first))))
    (getContextPath [_this]
      (let [context-path (.getPath (HttpExchange/.getHttpContext http-exchange))]
        (case context-path
          "/" ""
          context-path)))
    (isAsyncSupported [_this] true)
    (startAsync [this] @*async-context
      (.getAsyncContext this))
    (isAsyncStarted [_this] (realized? *async-context))
    (getRemoteAddr [_this] (.getHostAddress (.getAddress (HttpExchange/.getRemoteAddress http-exchange))))
    (getServerPort [_this] (.getPort (.getAddress (.getServer (HttpExchange/.getHttpContext http-exchange)))))
    (getHeaderNames [_this] (Collections/enumeration (.keySet (HttpExchange/.getRequestHeaders http-exchange))))
    (getHeaders [_this name] (Collections/enumeration (.get (HttpExchange/.getRequestHeaders http-exchange) name)))
    (getHeader [_this name] (.getFirst (HttpExchange/.getRequestHeaders http-exchange) name))))

#_org.eclipse.jetty.server.Response
#_io.pedestal.http.impl.servlet-interceptor/send-response
(defn http-exchange->http-servlet-response
  [http-exchange *async-context]
  (let [*status (atom 200)
        *content-length (atom 0)
        *headers (delay (HttpExchange/.getResponseHeaders http-exchange))
        *response-body (delay
                         (HttpExchange/.sendResponseHeaders http-exchange @*status @*content-length)
                         (HttpExchange/.getResponseBody http-exchange))]
    (reify HttpServletResponse
      (getOutputStream [_]
        (proxy [ServletOutputStream] []
          (write [b off len]
            (OutputStream/.write @*response-body b off len))
          (close []
            (AutoCloseable/.close @*response-body))))
      (setStatus [_ status] (reset! *status status))
      (getStatus [_] @*status)
      (getBufferSize [_] 0)
      (setHeader [_ header value]
        (Map/.put @*headers header [value]))
      (addHeader [_ header value]
        (Headers/.add @*headers header value))
      (setContentType [_ content-type]
        (when content-type
          (Map/.put @*headers "Content-Type" [content-type])))
      (setContentLength [_ content-length]
        (reset! *content-length (long content-length)))
      (setContentLengthLong [_ content-length]
        (reset! *content-length (long content-length)))
      (flushBuffer [_]
        (try
          (OutputStream/.flush @*response-body)
          (catch IOException _))
        (when-not (realized? *async-context)
          (AutoCloseable/.close http-exchange)))
      (isCommitted [_]
        (realized? *response-body))
      container/WriteNIOByteBody
      (write-byte-channel-body [_this body resume-chan context]
        (async/put! @*async-context {:body          body
                                     :response-body @*response-body
                                     :resume-chan   resume-chan
                                     :context       context}))
      (write-byte-buffer-body [_this body resume-chan context]
        (async/put! @*async-context {:body          body
                                     :response-body @*response-body
                                     :resume-chan   resume-chan
                                     :context       context})))))

#_io.pedestal.http.jetty/create-server
(defn create-server
  [servlet {:keys [host port container-options]}]
  (let [{:keys [context-path configurator #_ssl-port #_insecure-ssl? #_keystore #_ssl?]
         :or   {context-path "/"
                configurator identity}} container-options
        *async-context (delay
                         (let [c (async/chan)]
                           (future
                             (loop []
                               (when-let [{:keys [body resume-chan context ^OutputStream response-body]} (async/<!! c)]
                                 (try
                                   (if (instance? ByteBuffer body)
                                     (.write (Channels/newChannel response-body) body)
                                     ;; TODO: Review performance!
                                     (let [bb (ByteBuffer/allocate #_0x10000 65536)]
                                       (loop []
                                         (let [n (ReadableByteChannel/.read body bb)]
                                           (when (pos-int? n)
                                             (.write response-body (.array bb) 0 n)
                                             (.clear bb)
                                             (recur))))))
                                   (async/put! resume-chan context)
                                   (async/close! resume-chan)
                                   (catch Throwable error
                                     (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error error))
                                     (async/close! resume-chan)))
                                 (recur))))
                           c))
        http-handler (reify HttpHandler
                       (handle [_this http-exchange]
                         (Servlet/.service servlet
                           (http-exchange->http-servlet-request http-exchange *async-context)
                           (http-exchange->http-servlet-response http-exchange *async-context))))
        addr (if (string? host)
               (InetSocketAddress. ^String host (int port))
               (InetSocketAddress. port))
        server (HttpServer/create addr 0)]
    (HttpServer/.createContext server context-path http-handler)
    (configurator server)))

#_io.pedestal.http.jetty/server
(defn server
  [{::http/keys [servlet]} {:keys [#_join?] :as options}]
  (let [server (create-server servlet options)]
    {:server   server
     :start-fn (fn []
                 (log/info :version :dev)
                 (HttpServer/.start server)
                 (log/info :started :server)
                 #_(when join?
                     (.join server)))
     :stop-fn  (fn []
                 (HttpServer/.stop server 0)
                 (log/info :stopped :server))}))

(defn create-connector
  [{:keys [port host #_join? initial-context interceptors]}]
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
            (if body
              (let [content-length (or (some-> response-headers
                                         (.getFirst "Content-Length")
                                         parse-long)
                                     0)]
                (with-open [output-stream (.getResponseBody http-exchange)]
                  (HttpExchange/.sendResponseHeaders http-exchange status content-length)
                  (rcp/write-body-to-stream body response output-stream)))
              (HttpExchange/.sendResponseHeaders http-exchange status -1))))))
    (reify
      p/PedestalConnector
      (start-connector! [this]
        (HttpServer/.start server)
        this)
      (stop-connector! [this]
        (HttpServer/.stop server 0)
        this)
      AutoCloseable
      (close [this]
        (HttpServer/.stop server 0)))))
