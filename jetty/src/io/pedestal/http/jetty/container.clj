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
  (:require [io.pedestal.http.container]
            [clojure.core.async])
  (:import java.nio.channels.ReadableByteChannel))

(extend-protocol servlet-interceptor/WriteBodyByteChannel
  org.eclipse.jetty.server.Response
  (write-body-byte-channel [servlet-response ^ReadableByteChannel body resume-chan context]
    (let [os ^org.eclipse.jetty.server.HttpOutput (.getOutputStream servlet-response)]
      (.sendContent os body-chan (reify org.eclipse.jetty.util.Callback
                                   (succeeded [this]
                                     (.close body-chan)
                                     (async/put! resume-chan context)
                                     (async/close! resume-chan))
                                   (failed [this throwable]
                                     (.close body-chan)
                                     (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))
                                     (async/close! resume-chan)))))))

