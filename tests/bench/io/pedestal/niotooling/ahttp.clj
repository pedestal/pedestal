(ns io.pedestal.niotooling.ahttp
;ns clj-ahttp.core
  (:refer-clojure :exclude (get))
  (:require [clojure.string :as cstr]
            [clojure.core.async :as async])
  (:import (com.ning.http.client AsyncHttpClient
                                 AsyncHttpClientConfig
                                 AsyncHttpClientConfig$Builder
                                 AsyncHandler
                                 AsyncHandler$STATE
                                 RequestBuilder
                                 HttpResponseHeaders)
           (java.nio ByteBuffer)
           (java.nio.channels Pipe
                              ReadableByteChannel)))

(defn request-method->str [request-method]
  (-> request-method
      name
      cstr/upper-case))

(defn build-request [{:keys [request-method uri headers] :as args}]
  (assert (string? uri))
  (let [builder (RequestBuilder. ^String (request-method->str request-method))]
    (.setUrl builder ^String uri)
    (.build builder)))

(defn process-output [{:keys [resp-chan
                              body-chan]}])

(defn request
  "Async HTTP request. Mostly compatible w/ clj-http.

options
:output - allowed options are :future, :async
"
  [{:keys [uri request-method headers] :as args}]
  (let [c (AsyncHttpClient.)]
    (.executeRequest c (build-request args))))

(defn response-headers->map [^HttpResponseHeaders rh]
  (->> (.getHeaders rh)
       (map (fn [[k v]]
              [k (first v)]))
       (into {})))


(defn client-config []
  (let [builder (AsyncHttpClientConfig$Builder.)]
    (doto builder
      (.setIdleConnectionInPoolTimeoutInMs 1))
    (.build builder)))

(def client (AsyncHttpClient. (client-config)))

(defn request
  "Makes an async http request. Behaves like clj-http, except the return type is

 {:status (async/chan Int)
  :headers (asnyc/chan {})
  :body java.nio.channels.ReadableByteChannel}

:status and :headers will only ever receive one value each.

A response map is returned immediately, potentially even before the server has sent an HTTP status.

The response map also contains a key, :abort!, a fn of no arguments. Call it to abort processing.

The body channel should be (.close)'d when done. Failing to close can lead to hangs on future clj-ahttp requests."
  [{:keys [handler] :as args}]
  (let [status (async/chan 1)
        headers (async/chan 1)
        pipe (Pipe/open)
        source (.source pipe)
        sink (.sink pipe)
        abort (atom false)
        return-state (fn []
                       (if @abort
                         AsyncHandler$STATE/ABORT
                         AsyncHandler$STATE/CONTINUE))
        resp {:status status
              :headers headers
              :body source
              :abort! (fn []
                        (swap! abort (constantly true)))}]
    (.executeRequest ^AsyncHttpClient client (build-request args)
                     (reify AsyncHandler
                       (onThrowable [this throwable]
                         (throw throwable))
                       (onStatusReceived [this s]
                         (async/put! status (.getStatusCode s))
                         (return-state))
                       (onHeadersReceived [this h]
                         (async/put! headers (response-headers->map h))
                         (return-state))
                       (onBodyPartReceived [this body-part]
                         (let [buf (.getBodyByteBuffer body-part)
                               buf-len (.remaining buf)
                               written (.write sink buf)]
                           (assert (= written buf-len)))
                         (return-state))
                       (onCompleted [this]
                         (.close sink))))
    resp))

(defn drain-resp
  "Convert to a normal clj-http-style response"
  [resp]
  (let [len (-> resp :headers async/<!! (clojure.core/get "Content-Length") (#(Long/parseLong %)))
        body-chan (-> resp :body)
        buf (ByteBuffer/allocate (* 2 len))]
    (loop []
      (let [ret (.read ^ReadableByteChannel body-chan buf)]
        (if (.hasRemaining buf)
          (if (= ret -1)
            (do
              (.close body-chan)
              (.flip buf)
              buf)
            (recur))
          (throw (java.nio.BufferOverflowException "out of space")))))))

(defn get [uri & [opts]]
  (request (merge opts
                  {:request-method :get
                   :uri uri})))

