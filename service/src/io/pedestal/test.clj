; Copyright 2013 Relevance, Inc.
; Copyright 2014-2019 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Pedestal testing utilities to simplify working with pedestal apps."}
 io.pedestal.test
  (:require [io.pedestal.http.servlet :as servlets]
            [io.pedestal.log :as log]
            [clojure.string :as cstr]
            [clojure.java.io :as io]
            [clojure.core.async :as async]
            [io.pedestal.http.container :as container])
  (:import (javax.servlet.http HttpServlet HttpServletRequest HttpServletResponse)
           (javax.servlet Servlet ServletOutputStream ServletInputStream AsyncContext)
           (java.io ByteArrayInputStream ByteArrayOutputStream InputStream OutputStream)
           (clojure.lang IMeta)
           (java.util Enumeration NoSuchElementException)
           (java.nio.channels Channels ReadableByteChannel)))

(defn- ^Servlet test-servlet
  [interceptor-service-fn]
  (servlets/servlet :service interceptor-service-fn))

(defn parse-url
  [url]
  (let [[all scheme raw-host path query-string] (re-matches #"(?:([^:]+)://)?([^/]+)?(?:/([^\?]*)(?:\?(.*))?)?" url)
        [host port] (when raw-host (cstr/split raw-host #":"))]
    {:scheme scheme
     :host host
     :port (if port
             (Integer/parseInt port)
             -1)
     :path path
     :query-string query-string}))

(defn- enumerator
  [data kw]
  (let [data (atom (seq data))]
    (reify Enumeration
      (hasMoreElements [this] (not (nil? (first @data))))
      (nextElement [this]
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
    (proxy [ServletInputStream] []
      (read ([] -1)
        ([^bytes b] -1)
        ([^bytes b ^Integer off ^Integer len] -1))
      (readLine [bytes off len] -1)))

  String
  (->servlet-input-stream [string]
    (->servlet-input-stream (ByteArrayInputStream. (.getBytes string))))

  InputStream
  (->servlet-input-stream [wrapped-stream]
    (proxy [ServletInputStream] []
      (available ([] (.available wrapped-stream)))
      (read ([] (.read wrapped-stream))
        ([^bytes b] (.read wrapped-stream b))
        ([^bytes b ^Integer off ^Integer len] (.read wrapped-stream b off len))))))

(defn- test-servlet-input-stream
  ([] (test-servlet-input-stream nil))
  ([input] (->servlet-input-stream input)))

(defn- test-servlet-request
  [verb url & args]
  (let [{:keys [scheme host port path query-string]} (parse-url url)
        options (apply array-map args)
        async-context (atom nil)
        completion (promise)
        meta-data {:completion completion}]
    (assert (every? some? (vals (:headers options)))
            (str "You called `response-for` with header values that were nil.
                 Nil header values don't conform to the Ring spec: " (pr-str (:headers options))))
    (with-meta
      (reify HttpServletRequest
        (getMethod [this] (-> verb
                              name
                              cstr/upper-case))
        (getRequestURL [this] (StringBuffer. url))
        (getServerPort [this] port)
        (getServerName [this] host)
        (getRemoteAddr [this] "127.0.0.1")
        (getRemotePort [this] 0)
        (getRequestURI [this] (str "/" path))
        (getServletPath [this] (.getRequestURI this))
        (getContextPath [this] "")
        (getQueryString [this] query-string)
        (getScheme [this] scheme)
        (getInputStream [this] (apply test-servlet-input-stream (when-let [body (:body options)] [body])))
        (getProtocol [this] "HTTP/1.1")
        (isAsyncSupported [this] true)
        (isAsyncStarted [this] (some? @async-context))
        (getAsyncContext [this] @async-context)
        (startAsync [this]
          (compare-and-set! async-context
                            nil
                            (reify AsyncContext
                              (complete [this]
                                (deliver completion true)
                                nil)
                              (setTimeout [this n]
                                nil)
                              (start [this r]
                                nil)))
          @async-context)
        ;; Needed for NIO testing (see Servlet Interceptor)
        (getHeaderNames [this] (enumerator (keys (get options :headers)) ::getHeaderNames))
        (getHeader [this header] (get-in options [:headers header]))
        (getHeaders [this header] (enumerator [(get-in options [:headers header])] ::getHeaders))
        (getContentLength [this] (Integer/parseInt (get-in options [:headers "Content-Length"] "0")))
        (getContentLengthLong [this] (Long/parseLong (get-in options [:headers "Content-Length"] "0")))
        (getContentType [this] (get-in options [:headers "Content-Type"] ""))
        (getCharacterEncoding [this] "UTF-8")
        (setAttribute [this s obj] nil) ;; Needed for NIO testing (see Servlet Interceptor)
        (getAttribute [this attribute] nil))
      meta-data)))

(defn- test-servlet-output-stream
  []
  (let [output-stream (ByteArrayOutputStream.)]
    (proxy [ServletOutputStream IMeta] []
      (write
        ([arg] (if (= java.lang.Integer (type arg))
                 (.write output-stream (int arg))
                 (.write output-stream (bytes arg))))
        ([contents off len] (.write output-stream (bytes contents) (int off) (int len))))
      (meta [] {:output-stream output-stream}))))

(defn ^HttpServletResponse test-servlet-response
  "Returns a mock servlet response with a ServletOutputStream over a
  ByteArrayOutputStream. Captures the ByteArrayOutputStream in
  metadata. All headers set will swap a headers map held in an atom,
  also held in metadata."
  []
  (let [output-stream (test-servlet-output-stream)
        headers-map (atom {})
        status-val (atom nil)
        committed (atom false)
        meta-data {:output-stream (:output-stream (meta output-stream))
                   :status status-val
                   :headers-map headers-map}]
    (with-meta (reify HttpServletResponse
                 (getOutputStream [this] output-stream)
                 (setStatus [this status] (reset! status-val status))
                 (getStatus [this] @status-val)
                 (getBufferSize [this] 1500)
                 (setHeader [this header value] (swap! headers-map update-in [:set-header] assoc header value))
                 (addHeader [this header value] (swap! headers-map update-in [:added-headers header] conj value))
                 (setContentType [this content-type] (swap! headers-map assoc :content-type content-type))
                 (setContentLength [this content-length] (swap! headers-map assoc :content-length content-length))
                 (setContentLengthLong [this content-length] (swap! headers-map assoc :content-length content-length))
                 (flushBuffer [this] (reset! committed true))
                 (isCommitted [this] @committed)
                 (sendError [this sc]
                   (.sendError this sc "Server Error"))
                 (sendError [this sc msg]
                   (reset! status-val sc)
                   (io/copy msg output-stream))

                 ;; Force all async NIO behaviors to be sync
                 container/WriteNIOByteBody
                 (write-byte-channel-body [this body resume-chan context]
                   (let [instream-body (Channels/newInputStream ^ReadableByteChannel body)]
                     (try (io/copy instream-body output-stream)
                          (async/put! resume-chan context)
                          (catch Throwable t
                            (async/put! resume-chan (assoc context :io.pedestal.interceptor.chain/error t)))
                          (finally (async/close! resume-chan)))))
                 (write-byte-buffer-body [this body resume-chan context]
                   (let [out-chan (Channels/newChannel ^java.io.OutputStream output-stream)]
                     (try (.write out-chan body)
                          (async/put! resume-chan context)
                          (catch Throwable t
                            (async/put! resume-chan (assoc context :io.pedestal.interceptor.chain/error t)))
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
  "Return a ring response map for an HTTP request of type `verb`
  against url `url`, when applied to interceptor-service-fn. Useful
  for integration testing pedestal applications and getting all
  relevant middlewares invoked, including ones which integrate with
  the servlet infrastructure."
  [interceptor-service-fn verb url & args]
  (let [servlet (test-servlet interceptor-service-fn)
        servlet-request (apply test-servlet-request verb url args)
        servlet-response (test-servlet-response)
        context (.service servlet servlet-request servlet-response)]
    (when (.isAsyncStarted ^HttpServletRequest servlet-request)
      (-> servlet-request meta :completion deref))
    {:status (test-servlet-response-status servlet-response)
     :body (test-servlet-response-body servlet-response)
     :headers (test-servlet-response-headers servlet-response)}))

(defn raw-response-for
  "Return a ring response map for an HTTP request of type `verb`
  against url `url`, when applied to interceptor-service-fn. Useful
  for integration testing pedestal applications and getting all
  relevant middlewares invoked, including ones which integrate with
  the servlet infrastructure. The response body will be returned as
  a ByteArrayOutputStream.
  Options:

  :body : An optional string that is the request body.
  :headers : An optional map that are the headers"
  [interceptor-service-fn verb url & options]
  (let [servlet-resp (apply servlet-response-for interceptor-service-fn verb url options)]
    (log/debug :in :response-for
               :servlet-resp servlet-resp)
    (update-in servlet-resp [:headers] #(merge (:set-header %)
                                               (:added-headers %)
                                               (when-let [content-type (:content-type %)]
                                                 {"Content-Type" content-type})
                                               (when-let [content-length (:content-length %)]
                                                 {"Content-Length" content-length})))))

(defn response-for
  "Return a ring response map for an HTTP request of type `verb`
  against url `url`, when applied to interceptor-service-fn. Useful
  for integration testing pedestal applications and getting all
  relevant middlewares invoked, including ones which integrate with
  the servlet infrastructure. The response body will be converted
  to a UTF-8 string.
  Options:

  :body : An optional string that is the request body.
  :headers : An optional map that are the headers"
  [interceptor-service-fn verb url & options]
  (-> (apply raw-response-for interceptor-service-fn verb url options)
      (update-in [:body] #(.toString ^ByteArrayOutputStream % "UTF-8"))))
