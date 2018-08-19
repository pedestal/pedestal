(ns io.pedestal.ions
  (:require [clojure.java.io :as io]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [ring.util.response :as ring-response])
  (:import [java.io ByteArrayOutputStream]))

(defprotocol IonizeBody
  (default-content-type [body] "Get default HTTP content-type for `body`.")
  (ionize [body] "Transforms body to a value type supported by Datomic Ions."))

(extend-protocol IonizeBody

  (class (byte-array 0))
  (default-content-type [_] "application/octet-stream")
  (ionize [byte-array]
    (io/input-stream byte-array))

  String
  (default-content-type [_] "text/plain")
  (ionize [string] string)

  clojure.lang.IPersistentCollection
  (default-content-type [_] "application/edn")
  (ionize [o] (pr-str o))

  clojure.lang.Fn
  (default-content-type [_] nil)
  (ionize [f]
    (let [o (ByteArrayOutputStream.)]
      (f o)
      (-> o .toByteArray ionize)))

  java.io.File
  (default-content-type [_] "application/octet-stream")
  (ionize [file]
    ;; should be able to return the file...
    (io/input-stream file))

  java.io.InputStream
  (default-content-type [_] "application/octet-stream")
  (ionize [input-stream] input-stream)

  nil
  (default-content-type [_] nil)
  (ionize [_] ()))

(def terminator-injector
  (interceptor/interceptor {:name ::terminator-injector
                            :enter (fn [ctx]
                                     (chain/terminate-when ctx #(ring-response/response? (:response %))))}))

(defn- assoc-error-response
  [ctx message]
  (log/info :msg "sending error" :message message)
  (assoc ctx :response {:status 500 :body message}))

(defn- set-default-content-type
  [{:keys [headers body] :or {headers {}} :as resp}]
  (let [content-type (headers "Content-Type")]
    (update-in resp [:headers] merge {"Content-Type" (or content-type
                                                       (default-content-type body))})))
(defn- ionize-body
  [resp]
  (update resp :body ionize))

(def ring-response
  (interceptor/interceptor {:name ::ring-response
                            :leave (fn [ctx]
                                     (if (:response ctx)
                                       (update ctx :response (comp ionize-body set-default-content-type))
                                       (assoc-error-response ctx "Internal server error: no response.")))
                            :error (fn [ctx ex]
                                     (log/error :msg "error response triggered"
                                       :exception ex
                                       :context ctx)
                                     (assoc-error-response ctx "Internal server error: exception."))}))

(defn- add-content-type
  [req]
  (if-let [ctype (get-in req [:headers "content-type"])]
    (assoc req :content-type ctype)
    req))

(defn- add-content-length
  [req]
  (if-let [clength (get-in req [:headers "content-length"])]
    (assoc req :content-length clength)
    req))

(defn ion-provider
  "Given a service map, returns a handler function which consumes ring requests
  and returns ring responses suitable for Datomic Ion consumption."
  [service-map]
  (let [interceptors (into [terminator-injector ring-response] (:io.pedestal.http/interceptors service-map))]
    (fn [{:keys [uri] :as request}]
      (let [initial-context  {:request (-> request
                                           (assoc :path-info uri)
                                           add-content-type
                                           add-content-length)}
            response-context (chain/execute initial-context interceptors)]
        (:response response-context)))))
