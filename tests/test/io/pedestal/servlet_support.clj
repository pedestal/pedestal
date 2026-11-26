; Copyright 2026 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.servlet-support
  "Test helper (not a test namespace): runs a connector map through the Jakarta Servlet API without
  starting a servlet container, using the mock servlet objects of io.pedestal.servlet.mock.

  Pedestal 1.0 removed io.pedestal.test, whose response-for did this for the io.pedestal.http service map.
  io.pedestal.connector.test/response-for does not go through the Servlet API (it executes the interceptor
  chain directly), so tests of servlet behavior (writing response bodies, NIO and async responses,
  the 500 response for an uncaught exception, ...) use this namespace instead.

  The servlet is either:

  - :connector-servlet (the default): an io.pedestal.servlet.ConnectorServlet whose ConnectorBridge is
    created by io.pedestal.connector.servlet/create-bridge, as when deploying a WAR file.
  - :fn-servlet: an io.pedestal.servlet.FnServlet (io.pedestal.http.servlet/servlet) running
    io.pedestal.http.impl.servlet-interceptor/http-interceptor-service-fn; this is what
    io.pedestal.http/create-servlet built in 0.8."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [io.pedestal.connector.servlet :as connector-servlet]
            [io.pedestal.http.impl.servlet-interceptor :as si]
            [io.pedestal.http.servlet :as servlet])
  (:import (io.pedestal.servlet ConnectorServlet)
           (io.pedestal.servlet.mock MockState)
           (jakarta.servlet Servlet ServletConfig)
           (java.io BufferedInputStream ByteArrayInputStream File InputStream)
           (java.util HashMap Map$Entry)))

;; NIO bodies (ByteBuffer, ReadableByteChannel) are written by the default implementation of
;; io.pedestal.http.container/WriteNIOByteBody for any HttpServletResponse (io.pedestal.test, removed in 1.0,
;; extended the protocol to the mock response itself).

(def ^:dynamic *connector-map* nil)

(def ^:dynamic *bridge-options* nil)

(defn create-bridge
  "The io.pedestal.connector.bridge-fn of the ConnectorServlets created by [[create-servlet]]."
  [servlet]
  (connector-servlet/create-bridge servlet *connector-map* *bridge-options*))

(defn create-servlet
  "Creates and initializes a servlet for the connector map (only its :interceptors and :initial-context are used).

  Options:
  :servlet - :connector-servlet (default) or :fn-servlet
  Other options (such as :exception-analyzer) are passed to create-bridge or http-interceptor-service-fn."
  (^Servlet [connector-map]
   (create-servlet connector-map nil))
  (^Servlet [connector-map options]
   (let [{servlet-kind :servlet
          :or          {servlet-kind :connector-servlet}} options
         options' (dissoc options :servlet)]
     (case servlet-kind
       :connector-servlet
       (let [servlet (ConnectorServlet.)
             params  {"io.pedestal.connector.bridge-fn" (str `create-bridge)}
             config  (reify ServletConfig
                       (getInitParameter [_ k]
                         (get params k)))]
         (binding [*connector-map*  connector-map
                   *bridge-options* options']
           (.init servlet config))
         servlet)

       :fn-servlet
       (let [{:keys [interceptors initial-context]} connector-map]
         (servlet/servlet :service (si/http-interceptor-service-fn interceptors initial-context options')))))))

;; The rest is io.pedestal.test/response-for, unchanged except that it takes a servlet.

(defn ^:private parse-url
  [url]
  (let [[_ scheme raw-host path query-string] (re-matches #"(?:([^:]+)://)?([^/]+)?(?:/([^\?]*)(?:\?(.*))?)?" url)
        [host port] (when raw-host (string/split raw-host #":"))]
    {:scheme       scheme
     :host         host
     :port         (if port
                     (Integer/parseInt port)
                     -1)
     :path         path
     :query-string query-string}))

(defprotocol PrepareRequestBody
  (body->input-stream [input]))

(extend-protocol PrepareRequestBody

  nil
  (body->input-stream [_]
    (body->input-stream ""))

  String
  (body->input-stream [string]
    (ByteArrayInputStream. (.getBytes string "UTF-8")))

  File
  (body->input-stream [file]
    (io/input-stream file))

  InputStream
  (body->input-stream [is]
    (if (instance? BufferedInputStream is)
      is
      (BufferedInputStream. is 8000))))

(defn ^:private create-request-headers
  [headers]
  (let [result (HashMap.)]
    (assert (every? string? (keys headers))
            ":headers option: keys must be strings")
    (assert (every? string? (vals headers))
            ":headers option: values must be strings")
    (doseq [[k v] headers]
      (.put result k v))
    result))

(defn ^:private new-mock-state
  ^MockState [verb url {:keys [body headers]
                        :or   {body ""}}]
  (let [{:keys [scheme host port path query-string]} (parse-url url)]
    (MockState.
      url
      (-> verb name string/upper-case)
      scheme
      host
      port
      path
      query-string
      (create-request-headers headers)
      (body->input-stream body))))

(defn ^:private extract-headers
  [^MockState state]
  (let [set-headers (reduce (fn [result ^Map$Entry e]
                              (assoc result (.getKey e) (.getValue e)))
                            {}
                            (-> state .-setResponseHeaders .entrySet))]
    (reduce (fn [result ^Map$Entry e]
              (assoc result (.getKey e) (-> e .getValue vec)))
            set-headers
            (-> state .-addedResponseHeaders .entrySet))))

(defn response-for
  "Returns a Ring response map for a request through the servlet's service method.

  A synchronous response is complete when the servlet returns. When the interceptor
  chain goes asynchronous, this waits for the AsyncContext to be completed, up to
  the :timeout option (in milliseconds, default 5000).

  The :body is a String (empty when there is no body); the Content-Length header, when set, is a number.

  Options:
  :body    - nil, String, File, or InputStream
  :headers - map of String to String
  :timeout - see above"
  [^Servlet servlet verb url & {:keys [timeout]
                                :or   {timeout 5000}
                                :as   options}]
  (let [state            (new-mock-state verb url options)
        servlet-request  (.-request state)
        servlet-response (.-response state)]
    (.service servlet servlet-request servlet-response)
    (when (and (.-asyncStarted state)
               (not (.waitForCompletion state timeout)))
      (throw (ex-info (str "Operation did not complete within " timeout " ms")
                      {:verb    verb
                       :url     url
                       :options options})))
    (let [content-length (.-responseContentLength state)]
      (cond-> {:status  (.-responseStatus state)
               :headers (extract-headers state)
               :body    (.toString (.-responseStream state) "UTF-8")}
        (pos? content-length) (assoc-in [:headers "Content-Length"] content-length)))))

(defn create-responder
  "Creates a servlet for the connector map (see [[create-servlet]]) and returns a function
  of [verb url & options] that calls [[response-for]]."
  ([connector-map]
   (create-responder connector-map nil))
  ([connector-map servlet-options]
   (let [servlet (create-servlet connector-map servlet-options)]
     (fn [verb url & {:as options}]
       (response-for servlet verb url options)))))
