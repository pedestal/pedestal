; Copyright 2024-2026 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.response
  "Utilities used to write Ring responses."
  {:added "0.7.0"}
  (:require [io.pedestal.json :as json]
            [clojure.java.io :as io]
            [cognitect.transit :as transit]
            [io.pedestal.interceptor.chain :as interceptor.chain]
            [ring.util.response :as ring-response])
  (:import (java.io OutputStreamWriter)))

;; Support for things in io.pedestal.http that are deprecated in 0.7.0

(defn- capture-output-to-stream
  [f]
  (fn [output-stream]
    (with-open [writer (OutputStreamWriter. output-stream)]
      (binding [*out* writer]
        (f))
      (.flush writer))))

(defn- data-response
  "Given printing function f (which writes to *out*), returns a response wrapped around
  a function that will stream a response to.

  This is sloppy and the corresponding i.o.p.http functions have been deprecated or rewritten."
  [f content-type]
  (ring-response/content-type
    (ring-response/response (capture-output-to-stream f))
    content-type))

;; Modern stuff

(defn- stream-xform
  "Returns a Pedestal response function; this will be passed an output stream to which the actual response
  should be written."
  [obj writer-fn]
  (fn [output-stream]
    (with-open [writer (io/writer output-stream)]
      (writer-fn obj writer))))

(defn stream-json
  [obj]
  (stream-xform obj json/stream-json))

(defn stream-transit
  [obj transit-format transit-opts]
  (fn [output-stream]
    (transit/write
      (transit/writer output-stream transit-format transit-opts)
      obj)))

(defn edn-response
  "Return a Ring response that will print the given `obj` to the HTTP output stream in EDN format."
  {:added      "0.8.0"
   :deprecated "0.8.0"}
  [obj]
  (data-response #(pr obj) "application/edn;charset=UTF-8"))

(defn json-response
  "Return a Ring response that will print the given `obj` to the HTTP output stream in JSON format."
  {:added "0.8.0"}
  [obj]
  (ring-response/content-type
    (ring-response/response
      (stream-json obj))
    "application/json;charset=UTF-8"))

(defn response?
  "A valid response is any map that includes an integer :status
  value."
  {:added "0.8.0"}
  [resp]
  (and (map? resp)
       (integer? (:status resp))))

(defn disable-response
  "Updates the context to identify that no response is expected; this typically is because
   the request was upgraded to a WebSocket connection."
  {:added "0.8.0"}
  [context]
  (assoc context ::response-disabled true))

(defn response-expected?
  "Returns true unless [[disable-response]] was previously invoked."
  {:added "0.8.0"}
  [context]
  (-> context ::response-disabled not))

(defn respond-with
  "Utility function to add a :response map to the interceptor context."
  {:added "0.8.0"}
  ([context status]
   (assoc context :response {:status status}))
  ([context status body]
   (assoc context :response {:status status
                             :body   body}))
  ([context status headers body]
   (assoc context :response {:status  status
                             :headers headers
                             :body    body})))

(defn- describe-value
  [value]
  (if (or (number? value) (keyword? value) (symbol? value) (boolean? value) (char? value))
    (str (pr-str value) " (a " (.getName (class value)) ")")
    (str "a " (.getName (class value)))))

(defn- describe-map
  "Describes a map by its keys only, as the values may be large or sensitive."
  [m]
  (let [ks (->> m keys (sort-by str))]
    (str (if (seq ks)
           (str "a map with keys " (pr-str (vec (take 10 ks))) (when (< 10 (count ks)) " (and more)"))
           "an empty map")
         (when (contains? m :request-method)
           " (it looks like the request map)"))))

(defn- invalid-response
  [context problem]
  (let [{:keys [response route]} context
        route-name (:route-name route)]
    (ex-info (str problem
                  (when route-name
                    (str ", in route " route-name))
                  ". A response must be a map with a positive integer :status."
                  " A function in an interceptor list is called as a handler, so its return value becomes"
                  " the :response; to change the context instead, use an interceptor with an :enter function.")
             (cond-> {:response response}
               route-name (assoc :route-name route-name)))))

;; Called after each interceptor's :enter. The exception thrown for an invalid :response is caught by
;; the interceptor chain, which adds the name of the interceptor and switches to the :error stage.
;; This stays a private Var with the same signature: some libraries replace it (via alter-var-root).
(defn- terminate-when-response*
  [{:keys [response] :as context}]
  (cond
    (nil? response) false

    (not (map? response))
    (throw (invalid-response context
                             (str "Interceptor attached a :response that is not a map: "
                                  (describe-value response))))

    (let [status (:status response)]
      (not (and (int? status)
                (pos? status))))
    (throw (invalid-response context
                             (if (contains? response :status)
                               (str "Interceptor attached a :response map whose :status is not a positive integer: "
                                    (pr-str (:status response)))
                               (str "Interceptor attached a :response map without a :status: "
                                    (describe-map response)))))

    ;; Explicitly do *not* check for :headers or :body

    :else
    true))

(defn terminate-when-response
  "Adds an interceptor chain terminator to terminate execution when a valid :response map
  is added to the context."
  {:added "0.8.0"}
  [context]
  (interceptor.chain/terminate-when context terminate-when-response*))
