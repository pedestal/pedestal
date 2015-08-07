; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.jetty.container
  (:require [io.pedestal.http.container :as container]
            [clojure.core.async :as async])
  (:import (java.nio.channels ReadableByteChannel)
           (java.nio ByteBuffer)
           (org.eclipse.jetty.server Response)))

(extend-protocol container/WriteNIOByteBody
  org.eclipse.jetty.server.Response
  (write-byte-channel-body [servlet-response ^ReadableByteChannel body resume-chan context]
    (let [os ^org.eclipse.jetty.server.HttpOutput (.getHttpOutput servlet-response)]
      (.sendContent os body (reify org.eclipse.jetty.util.Callback
                                   (succeeded [this]
                                     (.close body)
                                     (async/put! resume-chan context)
                                     (async/close! resume-chan))
                                   (failed [this throwable]
                                     (.close body)
                                     (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))
                                     (async/close! resume-chan))))))
  (write-byte-buffer-body [servlet-response ^ByteBuffer body resume-chan context]
    (let [os ^org.eclipse.jetty.server.HttpOutput (.getHttpOutput servlet-response)]
      (.sendContent os body (reify org.eclipse.jetty.util.Callback
                                   (succeeded [this]
                                     (async/put! resume-chan context)
                                     (async/close! resume-chan))
                                   (failed [this throwable]
                                     (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))
                                     (async/close! resume-chan)))))))

