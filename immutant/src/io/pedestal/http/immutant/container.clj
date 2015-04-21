; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.immutant.container
  (:require [io.pedestal.http.container :as container]
            [clojure.core.async :as async])
  (:import (java.nio.channels ReadableByteChannel)
           (java.nio ByteBuffer)
           (javax.servlet AsyncContext)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           (org.xnio Pool Pooled)
           (io.undertow.servlet.spec HttpServletResponseImpl ServletOutputStreamImpl)))

(defn- fill-buffer
  "A blocking read that returns boolean indicating EOF"
  [^ReadableByteChannel body ^ByteBuffer buffer]
  (> 0 (loop []
         (let [c (.read body buffer)]
           (if (and (< 0 c) (.hasRemaining buffer))
             (recur)
             c)))))

(defn- write-channel
  [^ServletOutputStreamImpl os ^ReadableByteChannel body ^Pool buffer-pool]
  (let [pooled ^Pooled (.allocate buffer-pool)
        buffer ^ByteBuffer (.getResource pooled)]
    (try
      (loop []
        (when (.isReady os)
          (.clear buffer)
          (let [eof (fill-buffer body buffer)]
            (.flip buffer)
            (.write os buffer)
            (or eof (recur)))))
      (finally
        (.free pooled)))))

(extend-protocol container/WriteNIOByteBody
  HttpServletResponseImpl
  (write-byte-channel-body [servlet-response ^ReadableByteChannel body resume-chan context]
    (let [;; Unlike Jetty, Undertow needs to toggle into Async mode to send the NIO payloads
          servlet-req ^HttpServletRequest (:servlet-request context)
          ac ^AsyncContext (.startAsync servlet-req)
          os ^ServletOutputStreamImpl (.getOutputStream servlet-response)
          pool (-> servlet-response .getExchange .getConnection .getBufferPool)]
      (.setWriteListener os (reify javax.servlet.WriteListener
                              (onWritePossible [this]
                                (when (write-channel os body pool)
                                  (.close body)
                                  (async/put! resume-chan context)
                                  (async/close! resume-chan)
                                  (.complete ac)))
                              (onError [this throwable]
                                (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))
                                (async/close! resume-chan))))))
  (write-byte-buffer-body [servlet-response ^ByteBuffer body resume-chan context]
    (let [;; Unlike Jetty, Undertow needs to toggle into Async mode to send the NIO payloads
          servlet-req ^HttpServletRequest (:servlet-request context)
          ac ^AsyncContext (.startAsync servlet-req)
          os ^ServletOutputStreamImpl (.getOutputStream servlet-response)]
      (.setWriteListener os (reify javax.servlet.WriteListener
                              (onWritePossible [this]
                                (when (.isReady os)
                                  (.write os body)
                                  (async/put! resume-chan context)
                                  (async/close! resume-chan)
                                  (.complete ac)))
                              (onError [this throwable]
                                (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))
                                (async/close! resume-chan)))))))
