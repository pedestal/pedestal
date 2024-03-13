; Copyright 2024 Nubank NA
; Copyright 2014-2022 Cognitect, Inc.
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.request.servlet-support
  "Extends the ContainerRequest and ResponseBuffer protocols to the Jakarta Servlet interfaces."
  (:require [clojure.string :as string]
            [io.pedestal.http.request :as request])
  (:import (jakarta.servlet.http HttpServletRequest HttpServletResponse)))

(defn servlet-request-headers [^HttpServletRequest servlet-req]
  (loop [out   (transient {})
         names (enumeration-seq (.getHeaderNames servlet-req))]
    (if (seq names)
      (let [^String key (first names)
            hdrstr      (string/join "," (enumeration-seq (.getHeaders servlet-req key)))]
        (recur (assoc! out (.toLowerCase key) hdrstr)
               (rest names)))
      (persistent! out))))

(defn servlet-path-info [^HttpServletRequest request]
  (let [path-info (.substring (.getRequestURI request)
                              (.length (.getContextPath request)))]
    (if (.isEmpty path-info)
      "/"
      path-info)))

(extend-protocol request/ContainerRequest
  HttpServletRequest
  (server-port [req] (.getServerPort req))
  (server-name [req] (.getServerName req))
  (remote-addr [req] (.getRemoteAddr req))
  (uri [req] (.getRequestURI req))
  (query-string [req] (.getQueryString req))
  (scheme [req] (keyword (.getScheme req)))
  (request-method [req] (keyword (.toLowerCase (.getMethod req))))
  (protocol [req] (.getProtocol req))
  (headers [req] (servlet-request-headers req))
  (header [req header-string] (.getHeader req header-string))
  (ssl-client-cert [req] (.getAttribute req "jakarta.servlet.request.X509Certificate"))
  (body [req] (.getInputStream req))
  (path-info [req] (servlet-path-info req))
  (async-supported? [req] (.isAsyncSupported req))
  (async-started? [req] (.isAsyncStarted req)))

(extend-protocol request/ResponseBuffer
  HttpServletResponse
  (response-buffer-size [resp]

    (.getBufferSize ^HttpServletResponse resp)))
