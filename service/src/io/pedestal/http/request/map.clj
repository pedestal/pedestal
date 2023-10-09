(ns io.pedestal.http.request.map
  (:require [io.pedestal.http.request :as request])
  (:import (jakarta.servlet Servlet ServletConfig ServletRequest)
           (jakarta.servlet.http HttpServletRequest HttpServletResponse)))

(defn add-content-type [req-map ^HttpServletRequest servlet-req]
  (if-let [ctype (.getContentType servlet-req)]
    (let [headers (:headers req-map)]
      (-> (assoc! req-map :content-type ctype)
          (assoc! :headers (assoc headers "content-type" ctype))))
    req-map))

(defn add-content-length [req-map ^HttpServletRequest servlet-req]
  (let [c (.getContentLengthLong servlet-req)
        headers (:headers req-map)]
    (if (neg? c)
      req-map
      (-> (assoc! req-map :content-length c)
          (assoc! :headers (assoc headers "content-length" (str c)))))))

(defn add-character-encoding [req-map ^HttpServletRequest servlet-req]
  (if-let [e (.getCharacterEncoding servlet-req)]
    (assoc! req-map :character-encoding e)
    req-map))

(defn add-ssl-client-cert [req-map ^HttpServletRequest servlet-req]
  (if-let [c (.getAttribute servlet-req "jakarta.servlet.request.X509Certificate")]
    (assoc! req-map :ssl-client-cert c)
    req-map))

(defn servlet-request-map [^Servlet servlet ^HttpServletRequest servlet-req servlet-resp]
  (-> (request/base-request-map servlet-req)
      transient
      (add-content-length servlet-req)
      (add-content-type servlet-req)
      (add-character-encoding servlet-req)
      (add-ssl-client-cert servlet-req)
      (assoc! :servlet servlet)
      (assoc! :servlet-request servlet-req)
      (assoc! :servlet-response servlet-resp)
      ;(assoc! :servlet-context (.getServletContext ^ServletConfig servlet))
      (assoc! :context-path (.getContextPath servlet-req)) ;; This is used in some scenarios with multiple-apps on the same server
      ;(assoc! :servlet-path (.getServletPath servlet-req))
      persistent!))
