; Copyright 2024 Nubank NA
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.jetty.container
  "Extends Pedestal protocols onto Jetty container classes."
  (:require [io.pedestal.http.container :as container]
            [clojure.core.async :as async])
  (:import (java.nio.channels ReadableByteChannel)
           (java.nio ByteBuffer)
           (org.eclipse.jetty.ee10.servlet HttpOutput ServletApiResponse ServletChannel)
           (org.eclipse.jetty.util Callback)))

(extend-protocol container/WriteNIOByteBody
  ServletApiResponse
  (write-byte-channel-body [servlet-response ^ReadableByteChannel body resume-chan context]
    (let [os ^HttpOutput (.getHttpOutput ^ServletChannel (.getServletChannel servlet-response))]
      (.sendContent os body (reify Callback
                                   (succeeded [_]
                                     (.close body)
                                     (async/put! resume-chan context)
                                     (async/close! resume-chan))
                                   (failed [_ throwable]
                                     (.close body)
                                     (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))
                                     (async/close! resume-chan))))))
  (write-byte-buffer-body [servlet-response ^ByteBuffer body resume-chan context]
    (let [os ^HttpOutput (.getHttpOutput ^ServletChannel (.getServletChannel servlet-response))]
      (.sendContent os body (reify Callback
                                   (succeeded [_]
                                     (async/put! resume-chan context)
                                     (async/close! resume-chan))
                                   (failed [_ throwable]
                                     (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))
                                     (async/close! resume-chan)))))))
