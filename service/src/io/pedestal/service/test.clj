; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.test
  "Utilities for PedestalConnectors to implement testing, and functions used when writing such tests."
  {:added "0.8.0"}
  (:require [clj-commons.ansi :as ansi]
            [clojure.java.io :as io]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.service.impl :as impl]
            [clojure.core.async :refer [<!!]]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.service.protocols :as p])
  (:import (clojure.core.async.impl.protocols Channel)
           (clojure.lang Fn IPersistentCollection)
           (java.io ByteArrayInputStream File InputStream)
           (java.nio ByteBuffer)
           (java.nio.channels ReadableByteChannel)))

(defprotocol RequestBodyConversion

  "Converts a supported type of Request body to an InputStream."

  (convert-request-body [this]
    "Convert a value for a Ring request map's :body to an InputStream, for downstream processing."))

(extend-protocol RequestBodyConversion

  nil
  (convert-request-body [_] nil)

  String
  (convert-request-body [s]
    (-> s
        (.getBytes "UTF-8")
        ByteArrayInputStream.))

  InputStream
  (convert-request-body [input-stream] input-stream)

  File
  (convert-request-body [file]
    (io/input-stream file)))


(defprotocol ResponseBodyConversion
  "Convert the body of the response to an InputStream (or nil)."

  (convert-response-body [this]
    "Converts the response body to nil, a String, or an InputStream."))


;; Need to keep this in sync with io.pedestal.http.impl.servlet-interceptor/WriteableBody

(extend-protocol ResponseBodyConversion
  nil
  (convert-response-body [_] nil)

  String
  (convert-response-body [s]
    (-> s (.getBytes "UTF-8") convert-response-body))

  InputStream
  (convert-response-body [stream] stream)

  File
  (convert-response-body [file]
    (io/input-stream file))

  ;; If going through the Jakarta Servlet code, these would be handled asynchronously;
  ;; but that doesn't make sense in a test, so instead we block while copying.

  ByteBuffer
  (convert-response-body [buffer]
    (impl/byte-buffer->input-stream buffer))

  ReadableByteChannel
  (convert-response-body [channel]
    (impl/byte-channel->input-stream channel))

  Channel                                                   ; from core.async
  (convert-response-body [chan]
    (let [body-value (<!! chan)]
      (convert-response-body body-value)))

  Fn
  (convert-response-body [f]
    (impl/function->input-stream f))

  IPersistentCollection
  (convert-response-body [coll]
    (-> coll pr-str convert-response-body)))

(extend (Class/forName "[B")

  ResponseBodyConversion

  {:convert-response-body
   (fn [^bytes bytes]
     (ByteArrayInputStream. bytes))})

(defn- capture-context
  [*prom]
  (interceptor {:name  ::capture-context
                :leave (fn [context]
                         (deliver *prom context)
                         context)}))

(defn execute-interceptor-chain
  "Executes the interceptor chain for a Ring request, and returns a Ring response.

  The :body of the Ring request is limited to nil, String, InputStream, or File.

  The :body of the returned response map will be nil, or InputStream."
  [initial-context interceptors request]
  (let [request'      (update request :body convert-request-body)
        *prom         (promise)
        interceptors' (into [(capture-context *prom)] interceptors)
        _             (-> initial-context
                          (assoc :request request')
                          (chain/execute interceptors'))
        context'      @*prom
        response      (:response context')]
    (when-not response
      (throw (ex-info "No :response provided after execution"
                      {:context context'
                       :request request})))
    (-> response
        (select-keys [:status :headers :body])
        (update :body convert-response-body))))

(defn disable-routing-table-output-fixture
  "A test fixture that disables printing of the routing table, even when development mode
   is enabled.  It also disables ANSI colors in any Pedestal console output
   (such as deprecation warnings)."
  [f]
  (binding [route/*print-routing-table* false
            ansi/*color-enabled*        false]
    (f)))

(defn- create-request-headers
  [headers]
  (reduce-kv (fn [m k v]
               (assoc m (name k) (name v)))
             {}
             headers))

(defn response-for
  "Works with a [[PedestalConnector]] to test a Ring request map; returns a Ring response map.

  The :body of the response map will be either nil, or an InputStream.

  Options:

  Key      | Value
  ---      |---
  :headers | Map; keys and values are converted from keyword or symbol to string
  :body    | Body to send (nil, String, File, InputStream)


  "
  [connector request-method url & {:as options}]
  (let [{:keys [headers body]} options
        request (merge
                  {:request-method request-method
                   :headers        (create-request-headers headers)
                   :body           body}
                  (impl/parse-url url))]
    (p/test-request connector request)))


