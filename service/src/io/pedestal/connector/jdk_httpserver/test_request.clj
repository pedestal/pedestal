(ns io.pedestal.connector.jdk-httpserver.test-request
  (:require [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (clojure.lang IDeref)
           (com.sun.net.httpserver Headers HttpExchange)
           (java.io ByteArrayOutputStream)
           (java.net URI)))

(defn http-exchange
  "Creates a mock implementation of com.sun.net.httpserver.HttpExchange.
  When deref'ed, it waits untli `.close` and returns a ring-like response."
  [{:keys [uri query-string headers request-method server-name server-port scheme protocol #_remote-addr body]
    :or   {server-name "0"
           protocol    "HTTP/1.1"
           scheme      :http
           server-port -1}
    :as   ring-request}]
  (let [baos (ByteArrayOutputStream.)
        response-headers (Headers. {})
        *response (promise)
        *status (promise)]
    (proxy [HttpExchange IDeref] []
      (deref [] @*response)
      (close []
        (let [status @*status
              headers (into {}
                        (map (fn [[K vs]]
                               (let [k (string/lower-case K)]
                                 [k (if (next vs)
                                      (string/join (case k
                                                     "cookie" ";"
                                                     ",")
                                        vs)
                                      (first vs))])))
                        response-headers)]
          (deliver *response (cond-> {:status status
                                      :body   (io/input-stream (.toByteArray baos))}
                               (seq headers) (assoc :headers headers)))))
      (getResponseBody [] baos)
      (getRequestBody [] (if (string? body)
                           (io/input-stream (.getBytes (str body)))
                           body))
      (getRequestURI [] (URI. (name scheme) nil server-name server-port uri query-string nil))
      (getRequestMethod [] (string/upper-case (name request-method)))
      (getResponseHeaders [] response-headers)
      (sendResponseHeaders [response-code _response-length]
        (deliver *status response-code))
      (getProtocol [] protocol)
      (getRemoteAddress [] #_remote-addr)
      (getRequestHeaders [] (Headers. (into (if (contains? ring-request :body)
                                              {"transfer-encoding" ["chunked"]}
                                              {})
                                        (map (fn [[k vs]]
                                               [k (if (coll? vs)
                                                    (vec vs)
                                                    [vs])]))
                                        headers))))))
