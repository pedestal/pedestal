(ns io.pedestal.http.request.map
  (:require [io.pedestal.http.request :as request])
  (:import (javax.servlet Servlet ServletConfig ServletRequest)
           (javax.servlet.http HttpServletRequest HttpServletResponse)))

(defn base-request-map [req]
  {:server-port       (request/server-port req)
   :server-name       (request/server-name req)
   :remote-addr       (request/remote-addr req)
   :uri               (request/uri req)
   :query-string      (request/query-string req)
   :scheme            (request/scheme req)
   :request-method    (request/request-method req)
   :headers           (request/headers req)
   :body              (request/body req)
   :path-info         (request/path-info req)
   :protocol         (request/protocol req)
   :async-supported? (request/async-supported? req)})

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
  (if-let [c (.getAttribute servlet-req "javax.servlet.request.X509Certificate")]
    (assoc! req-map :ssl-client-cert c)
    req-map))

(defn servlet-request-map [^Servlet servlet ^HttpServletRequest servlet-req servlet-resp]
  (-> ;(base-request-map servlet servlet-req servlet-resp)
      (base-request-map servlet-req)
      transient
      (add-content-length servlet-req)
      (add-content-type servlet-req)
      (add-character-encoding servlet-req)
      (add-ssl-client-cert servlet-req)
      (assoc! :servlet servlet)
      (assoc! :servlet-request servlet-req)
      (assoc! :servlet-response servlet-resp)
      ;(assoc! :servlet-context (.getServletContext ^ServletConfig servlet))
      ;(assoc! :context-path (.getContextPath servlet-req))
      ;(assoc! :servlet-path (.getServletPath servlet-req))
      persistent!))

