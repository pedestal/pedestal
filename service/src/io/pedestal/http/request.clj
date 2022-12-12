; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.request)

(defprotocol ProxyDatastructure
  (realized [this] "Return fully-realized version of underlying data structure."))

(extend-protocol ProxyDatastructure
  nil
  (realized [t] nil))

(defprotocol ContainerRequest
  (server-port [x])
  (server-name [x])
  (remote-addr [x])
  (uri [x])
  (query-string [x])
  (scheme [x])
  (request-method [x])
  (protocol [x])
  (headers [x])
  (header [x header-string])
  (ssl-client-cert [x])
  (body [x])
  (path-info [x])
  (async-supported? [x])
  (async-started? [x]))

(extend-protocol ContainerRequest
  nil
  (server-port [x] nil)
  (server-name [x] nil)
  (remote-addr [x] nil)
  (uri [x] nil)
  (query-string [x] nil)
  (scheme [x] nil)
  (request-method [x] nil)
  (protocol [x] nil)
  (headers [x] nil)
  (header [x header-string] nil)
  (ssl-client-cert [x] nil)
  (body [x] nil)
  (path-info [x] nil)
  (async-supported? [x] false)
  (async-started? [x] false))

(defprotocol ResponseBuffer
  (response-buffer-size [x]))

(def ring-dispatch
  {:server-port      server-port
   :server-name      server-name
   :remote-addr      remote-addr
   :uri              uri
   :query-string     query-string
   :scheme           scheme
   :request-method   request-method
   :headers          headers
   :ssl-client-cert  ssl-client-cert
   :body             body
   :path-info        path-info
   :protocol         protocol
   :async-supported? async-supported?})

(def nil-fn (constantly nil))

(defn base-request-map [req]
  {:server-port      (server-port req)
   :server-name      (server-name req)
   :remote-addr      (remote-addr req)
   :uri              (uri req)
   :query-string     (query-string req)
   :scheme           (scheme req)
   :request-method   (request-method req)
   :headers          (headers req)
   :body             (body req)
   :path-info        (path-info req)
   :protocol         (protocol req)
   :async-supported? (async-supported? req)})
