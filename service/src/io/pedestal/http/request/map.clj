; Copyright 2024-2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.request.map
  "Responsible for converting incoming HttpServletRequest into
  a Ring-compatible request map."
  (:require [clojure.string :as string])
  (:import (jakarta.servlet Servlet)
           (jakarta.servlet.http HttpServletRequest HttpServletResponse)))

(defn- add-content-type
  [req-map ^HttpServletRequest servlet-req]
  (if-let [ctype (.getContentType servlet-req)]
    (let [headers (:headers req-map)]
      (-> req-map
          (assoc! :content-type ctype)
          (assoc! :headers (assoc headers "content-type" ctype))))
    req-map))

(defn- add-content-length
  [req-map ^HttpServletRequest servlet-req]
  (let [c       (.getContentLengthLong servlet-req)
        headers (:headers req-map)]
    (if (neg? c)
      req-map
      (-> req-map
          (assoc! :content-length c)
          (assoc! :headers (assoc headers "content-length" (str c)))))))

(defn- add-character-encoding
  [req-map ^HttpServletRequest servlet-req]
  (if-let [e (.getCharacterEncoding servlet-req)]
    (assoc! req-map :character-encoding e)
    req-map))

(defn- add-ssl-client-cert
  [req-map ^HttpServletRequest servlet-req]
  (if-let [c (.getAttribute servlet-req "jakarta.servlet.request.X509Certificate")]
    (assoc! req-map :ssl-client-cert c)
    req-map))

(defn- servlet-request-headers
  [^HttpServletRequest servlet-req]
  (loop [out   (transient {})
         names (enumeration-seq (.getHeaderNames servlet-req))]
    (if (seq names)
      (let [^String key (first names)
            hdrstr      (string/join "," (enumeration-seq (.getHeaders servlet-req key)))]
        (recur (assoc! out (.toLowerCase key) hdrstr)
               (rest names)))
      (persistent! out))))

(defn- servlet-path-info
  [^HttpServletRequest request]
  (let [path-info (.substring (.getRequestURI request)
                              (.length (.getContextPath request)))]
    (if (.isEmpty path-info)
      "/"
      path-info)))


(defn servlet-request-map
  "Create a Ring-compatible map from the servlet, request, and response."
  [^Servlet servlet
   ^HttpServletRequest servlet-req
   ^HttpServletResponse servlet-resp]
  (-> {:server-port      (.getServerPort servlet-req)
       :server-name      (.getServerName servlet-req)
       :remote-addr      (.getRemoteAddr servlet-req)
       :uri              (.getRequestURI servlet-req)
       :query-string     (.getQueryString servlet-req)
       :scheme           (-> servlet-req .getScheme keyword)
       :request-method   (-> servlet-req .getMethod .toLowerCase keyword)
       :headers          (servlet-request-headers servlet-req)
       :body             (.getInputStream servlet-req)
       :path-info        (servlet-path-info servlet-req)
       :protocol         (.getProtocol servlet-req)
       :async-supported? (.isAsyncSupported servlet-req)
       ; This is used in some scenarios with multiple-apps on the same server
       :context-path     (.getContextPath servlet-req)
       :servlet          servlet
       :servlet-request  servlet-req
       :servlet-response servlet-resp}
      transient
      (add-content-length servlet-req)
      (add-content-type servlet-req)
      (add-character-encoding servlet-req)
      (add-ssl-client-cert servlet-req)
      persistent!))
