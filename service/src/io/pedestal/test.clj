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
  (:require [clojure.string :as string]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.servlet :as servlets]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.http.container :as container]
            [io.pedestal.log :as log]
            [clojure.core.async :refer [thread put! close!]]
            [clojure.string :as cstr]
            [clojure.java.io :as io]
            [clj-commons.ansi :as ansi])
  (:import (jakarta.servlet.http HttpServlet)
           (java.io ByteArrayInputStream InputStream BufferedInputStream File)
           (java.nio.channels Channels ReadableByteChannel)
           (java.util HashMap Map$Entry)
           (io.pedestal.servlet.mock MockHttpServletResponse MockState)))

(defn- test-servlet
  ^HttpServlet [interceptor-service-fn]
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

(defprotocol PrepareRequestBody
  "How to prepare a provided request body as an InputStream that can be used with the Servlet API."
  (body->input-stream [input]))

(extend-protocol PrepareRequestBody

  nil
  (->servlet-input-stream [_]
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

(extend-protocol container/WriteNIOByteBody

  MockHttpServletResponse

  ;; Implement async operations

  (write-byte-channel-body [response body resume-chan context]
    (let [instream-body (Channels/newInputStream ^ReadableByteChannel body)]
      (try (io/copy instream-body (.getOutputStream response))
           (put! resume-chan context)
           (catch Throwable t
             (put! resume-chan (chain/with-error context t)))
           (finally (close! resume-chan)))))

  (write-byte-buffer-body [response body resume-chan context]
    (let [out-chan (Channels/newChannel (.getOutputStream response))]
      (try (.write out-chan body)
           (put! resume-chan context)
           (catch Throwable t
             (put! resume-chan (chain/with-error context t)))
           (finally (close! resume-chan))))))

(defn- create-request-headers
  [headers]
  (let [result (HashMap.)]
    (assert (every? string? (keys headers))
            ":headers option: keys must be strings")
    (assert (every? string? (vals headers))
            ":headers option: values must be strings")

    (doseq [[k v] headers]
      (.put result k v))
    result))

(defn- new-mock-state
  ^MockState [verb url & {:keys [body headers] :as options
                          :or   {body ""}}]
  (let [{:keys [scheme host port path query-string]} (parse-url url)
        body-stream (body->input-stream body)]
    (MockState.
      url
      (-> verb name string/upper-case)
      scheme
      host
      port
      path
      query-string
      (create-request-headers headers)
      body-stream)))

(defn- extract-headers
  [^MockState state]
  (let [set-headers (->> (reduce (fn [result ^Map$Entry e]
                                   (assoc result
                                          (.getKey e)
                                          (.getValue e)))
                                 {}
                                 (-> state .-setResponseHeaders .entrySet)))]
    (->> (reduce (fn [result ^Map$Entry e]
                   (assoc result
                          (.getKey e)
                          (-> e .getValue vec)))
                 set-headers
                 (-> state .-addedResponseHeaders .entrySet)))))

(defn servlet-response-for
  "Return a Ring response map for an HTTP request of type `verb`
  against url `url`, when applied to interceptor-service-fn. Useful
  for integration testing pedestal applications and getting all
  relevant middlewares invoked, including ones which integrate with
  the servlet infrastructure."
  [interceptor-service-fn verb url & {:keys [timeout]
                                      :or   {timeout 5000}
                                      :as   options}]
  (let [servlet          (test-servlet interceptor-service-fn)
        state            (new-mock-state verb url options)
        servlet-request  (.-request state)
        servlet-response (.-response state)]
    (.service servlet servlet-request servlet-response)
    (when-not (.waitForCompletion state timeout)
      (throw (ex-info (str "Operation did not complete within " timeout " ms")
                      {:verb    verb
                       :url     url
                       :options options
                       :state   state})))
    {:status  (.-responseStatus state)
     :body    (.-responseStream state)
     :headers (extract-headers state)
     ::state  state}))

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
  [interceptor-service-fn verb url & {:as options}]
  (let [servlet-resp (servlet-response-for interceptor-service-fn verb url options)]
    (log/debug :in :response-for
               :servlet-resp servlet-resp)
    (let [{::keys [state]} servlet-resp
          content-length (.-responseContentLength state)]
      (cond-> servlet-resp
        (pos? content-length)
        (assoc-in [:headers "Content-Length"] content-length)))))

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
  :headers : An optional map that are the request headers"
  [interceptor-service-fn verb url & {:as options}]
  (let [{::keys [state] :as response} (raw-response-for interceptor-service-fn verb url options)
        body (-> state
                 .responseStream
                 (.toString "UTF-8"))]
    (assoc response :body body)))

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
