; Copyright 2024-2025 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.test
  "Pedestal testing utilities; mock implementations of the core Servlet API
  objects, to support fast integration testing without starting a servlet container,
  or opening a port for HTTP traffic."
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.servlet :as servlets]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.log :as log]
            [clojure.string :as cstr]
            [clojure.java.io :as io]
            [clj-commons.ansi :as ansi]
            [clojure.core.async :as async]
            [io.pedestal.http.container :as container])
  (:import (jakarta.servlet.http HttpServletRequest HttpServletResponse)
           (jakarta.servlet Servlet ServletOutputStream ServletInputStream AsyncContext)
           (java.io ByteArrayInputStream ByteArrayOutputStream InputStream)
           (clojure.lang IMeta)
           (java.util Enumeration NoSuchElementException)
           (java.nio.channels Channels ReadableByteChannel))
  (:import (java.io OutputStream)))

(defn- test-servlet
  ^Servlet [interceptor-service-fn]
  (servlets/servlet :service interceptor-service-fn))

(defn parse-url
  [url]
  (let [[_ scheme raw-host path query-string] (re-matches #"(?:([^:]+)://)?([^/]+)?(?:/([^\?]*)(?:\?(.*))?)?" url)
        [host port] (when raw-host (cstr/split raw-host #":"))]
    {:scheme       scheme
     :host         host
     :port         (if port
                     (Integer/parseInt port)
                     -1)
     :path         path
     :query-string query-string}))

(defn- enumerator
  [data kw]
  (let [data (atom (seq data))]
    (reify Enumeration
      (hasMoreElements [_] (not (nil? (first @data))))
      (nextElement [_]
        (log/debug :in :enumerator/nextElement
                   :data @data
                   :hasMoreElements (not (nil? (first @data)))
                   :first (first @data)
                   :rest (rest @data))
        (let [result (first @data)]
          (when (nil? result)
            (throw
              (NoSuchElementException. (str "Attempt to fetch element from " kw))))
          (swap! data rest)
          result)))))

(defprotocol TestRequestBody
  (->servlet-input-stream [input]))

(extend-protocol TestRequestBody

  nil
  (->servlet-input-stream [_]
    (proxy [ServletInputStream]
           []
      (read ([] -1)
        ([^bytes _b] -1)
        ([^bytes _b ^Integer _off ^Integer _len] -1))
      (readLine [_bytes _off _len] -1)))

  String
  (->servlet-input-stream [string]
    (->servlet-input-stream (ByteArrayInputStream. (.getBytes string))))

  InputStream
  (->servlet-input-stream [wrapped-stream]
    (proxy [ServletInputStream]
           []
      (available ([] (.available wrapped-stream)))
      (read ([] (.read wrapped-stream))
        ([^bytes b] (.read wrapped-stream b))
        ([^bytes b ^Integer off ^Integer len] (.read wrapped-stream b off len))))))

(defn- test-servlet-input-stream
  ([] (test-servlet-input-stream nil))
  ([input] (->servlet-input-stream input)))

(defn- test-servlet-request
  [verb ^String url & args]
  (let [{:keys [scheme host port path query-string]} (parse-url url)
        options       (apply array-map args)
        async-context (atom nil)
        completion    (promise)
        meta-data     {:completion completion}]
    (assert (every? some? (vals (:headers options)))
            (str "You called `response-for` with header values that were nil.
                 Nil header values don't conform to the Ring spec: " (pr-str (:headers options))))
    (with-meta
      (reify HttpServletRequest
        (getMethod [_] (-> verb
                           name
                           cstr/upper-case))
        (getRequestURL [_] (StringBuffer. url))
        (getServerPort [_] port)
        (getServerName [_] host)
        (getRemoteAddr [_] "127.0.0.1")
        (getRemotePort [_] 0)
        (getRequestURI [_] (str "/" path))
        (getServletPath [_] (.getRequestURI _))
        (getContextPath [_] "")
        (getQueryString [_] query-string)
        (getScheme [_] scheme)
        (getInputStream [_] (apply test-servlet-input-stream (when-let [body (:body options)] [body])))
        (getProtocol [_] "HTTP/1.1")
        (isAsyncSupported [_] true)
        (isAsyncStarted [_] (some? @async-context))
        (getAsyncContext [_] @async-context)
        (startAsync [_]
          (compare-and-set! async-context
                            nil
                            (reify AsyncContext
                              (complete [_]
                                (deliver completion true)
                                nil)
                              (setTimeout [_ _timeout]
                                nil)
                              (start [_ _runnable]
                                nil)))
          @async-context)
        ;; Needed for NIO testing (see Servlet Interceptor)
        (getHeaderNames [_] (enumerator (keys (get options :headers)) ::getHeaderNames))
        (getHeader [_ _header] (get-in options [:headers _header]))
        (getHeaders [_ _header] (enumerator [(get-in options [:headers _header])] ::getHeaders))
        (getContentLength [_] (Integer/parseInt (get-in options [:headers "Content-Length"] "0")))
        (getContentLengthLong [_] (Long/parseLong (get-in options [:headers "Content-Length"] "0")))
        (getContentType [_] (get-in options [:headers "Content-Type"] ""))
        (getCharacterEncoding [_] "UTF-8")
        (setAttribute [_ _s _obj] nil)                      ;; Needed for NIO testing (see Servlet Interceptor)
        (getAttribute [_ _attribute] nil))
      meta-data)))

(defn- test-servlet-output-stream
  []
  (let [output-stream (ByteArrayOutputStream.)]
    (proxy [ServletOutputStream IMeta]
           []
      (write
        ([arg] (if (= java.lang.Integer (type arg))
                 (.write output-stream (int arg))
                 (.write output-stream (bytes arg))))
        ([contents off len] (.write output-stream (bytes contents) (int off) (int len))))
      (meta [] {:output-stream output-stream}))))

(defn test-servlet-response
  "Returns a mock servlet response with a ServletOutputStream over a
  ByteArrayOutputStream. Captures the ByteArrayOutputStream in
  metadata. All headers set will swap a headers map held in an atom,
  also held in metadata."
  ^HttpServletResponse []
  (let [output-stream (test-servlet-output-stream)
        headers-map   (atom {})
        status-val    (atom nil)
        committed     (atom false)
        meta-data     {:output-stream (:output-stream (meta output-stream))
                       :status        status-val
                       :headers-map   headers-map}]
    (with-meta (reify HttpServletResponse
                 (getOutputStream [_] output-stream)
                 (setStatus [_ status] (reset! status-val status))
                 (getStatus [_] @status-val)
                 (getBufferSize [_] 1500)
                 (setHeader [_ header value] (swap! headers-map update :set-header assoc header value))
                 (addHeader [_ header value] (swap! headers-map update-in [:added-headers header] conj value))
                 (setContentType [_ content-type] (swap! headers-map assoc :content-type content-type))
                 (setContentLength [_ content-length] (swap! headers-map assoc :content-length content-length))
                 (setContentLengthLong [_ content-length] (swap! headers-map assoc :content-length content-length))
                 (flushBuffer [_] (reset! committed true))
                 (isCommitted [_] @committed)
                 (sendError [_ sc]
                   (.sendError _ sc "Server Error"))
                 (sendError [_ sc msg]
                   (reset! status-val sc)
                   (io/copy msg output-stream))

                 ;; Force all async NIO behaviors to be sync
                 container/WriteNIOByteBody
                 (write-byte-channel-body [_ body resume-chan context]
                   (let [instream-body (Channels/newInputStream ^ReadableByteChannel body)]
                     (try (io/copy instream-body output-stream)
                          (async/put! resume-chan context)
                          (catch Throwable t
                            (async/put! resume-chan (chain/with-error context t)))
                          (finally (async/close! resume-chan)))))
                 (write-byte-buffer-body [_ body resume-chan context]
                   (let [out-chan (Channels/newChannel ^OutputStream output-stream)]
                     (try (.write out-chan body)
                          (async/put! resume-chan context)
                          (catch Throwable t
                            (async/put! resume-chan (chain/with-error context t)))
                          (finally (async/close! resume-chan))))))

               meta-data)))

(defn test-servlet-response-status
  [test-servlet-response]
  (-> test-servlet-response
      meta
      :status
      deref))

(defn test-servlet-response-body
  [test-servlet-response]
  (let [^ByteArrayOutputStream baos (-> test-servlet-response
                                        meta
                                        :output-stream)]
    (doto baos
      (.flush)
      (.close))))

(defn test-servlet-response-headers
  [test-servlet-response]
  (-> test-servlet-response
      meta
      :headers-map
      deref))

(defn servlet-response-for
  "Return a Ring response map for an HTTP request of type `verb`
  against url `url`, when applied to interceptor-service-fn. Useful
  for integration testing pedestal applications and getting all
  relevant middlewares invoked, including ones which integrate with
  the servlet infrastructure."
  [interceptor-service-fn verb url & args]
  (let [servlet          (test-servlet interceptor-service-fn)
        servlet-request  (apply test-servlet-request verb url args)
        servlet-response (test-servlet-response)]
    (.service servlet servlet-request servlet-response)
    (when (.isAsyncStarted ^HttpServletRequest servlet-request)
      (-> servlet-request meta :completion deref))
    {:status  (test-servlet-response-status servlet-response)
     :body    (test-servlet-response-body servlet-response)
     :headers (test-servlet-response-headers servlet-response)}))

(defn raw-response-for
  "Return a Ring response map for an HTTP request of type `verb`
  against url `url`, when applied to interceptor-service-fn. Useful
  for integration testing pedestal applications and getting all
  relevant middlewares invoked, including ones which integrate with
  the servlet infrastructure. The response body will be returned as
  a ByteArrayOutputStream.

  Note that the `Content-Length` header, if present, will be a number,
  not a string.

  Options:
  :body : An optional string that is the request body.
  :headers : An optional map that are the headers"
  [interceptor-service-fn verb url & options]
  (let [servlet-resp (apply servlet-response-for interceptor-service-fn verb url options)]
    (log/debug :in :response-for
               :servlet-resp servlet-resp)
    (update servlet-resp :headers #(merge (:set-header %)
                                          (:added-headers %)
                                          (when-let [content-type (:content-type %)]
                                            {"Content-Type" content-type})
                                          (when-let [content-length (:content-length %)]
                                            {"Content-Length" content-length})))))

(defn response-for
  "Return a Ring response map for an HTTP request of type `verb`
  against url `url`, when applied to interceptor-service-fn. Useful
  for integration testing pedestal applications and getting all
  relevant middlewares invoked, including ones which integrate with
  the servlet infrastructure. The response body will be converted
  to a UTF-8 string.

  This builds on [[raw-response-for]], see a note there about headers.

  An empty response body will be returned as an empty string.

  Options:

  :body : An optional string that is the request body.
  :headers : An optional map that are the headers"
  [interceptor-service-fn verb url & options]
  (-> (apply raw-response-for interceptor-service-fn verb url options)
      (update :body #(.toString ^ByteArrayOutputStream % "UTF-8"))))

(defn create-responder
  "Given a service map, this returns a function that wraps [[response-for]].

  The returned function's signature is: [verb url & options]"
  {:added "0.8.0"}
  [service-map]
  (let [service-fn (-> service-map
                       http/create-servlet
                       ::http/service-fn)]
    (fn [verb url & options]
      (apply response-for service-fn verb url options))))

(defn disable-routing-table-output-fixture
  "A test fixture that disables printing of the routing table, even when development mode
   is enabled.  It also disables ANSI colors in any Pedestal console output
   (such as deprecation warnings)."
  {:added "0.7.0"}
  [f]
  (binding [route/*print-routing-table* false
            ansi/*color-enabled*        false]
    (f)))
