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
           (org.xnio Pool Pooled)
           (io.undertow.servlet.spec HttpServletResponseImpl ServletOutputStreamImpl)))

(defn write-channel
  "TODO: mitigate blocking read"
  [^ServletOutputStreamImpl os ^ReadableByteChannel body ^Pool buffer-pool]
  (let [pooled ^Pooled (.allocate buffer-pool)
        buffer ^ByteBuffer (.getResource pooled)]
    (try
      (loop []
        (when (.isReady os)
          (while (and (.hasRemaining buffer) (< 0 (.read body buffer))))
          (.flip buffer)
          (.write os buffer)
          (.clear buffer)
          (if (= -1 (.read body buffer))
            (do (.close body) true)
            (recur))))
      (finally
        (.free pooled)))))

(extend-protocol container/WriteNIOByteBody
  HttpServletResponseImpl
  (write-byte-channel-body [servlet-response ^ReadableByteChannel body resume-chan context]
    (let [;; TODO: Not sure we should be calling startAsync here
          ac ^AsyncContext (-> context :servlet-request .startAsync)
          os ^ServletOutputStreamImpl (.getOutputStream servlet-response)
          pool (-> servlet-response .getExchange .getConnection .getBufferPool)]
      (.setWriteListener os (reify javax.servlet.WriteListener
                              (onWritePossible [this]
                                (when (write-channel os body pool)
                                  (async/put! resume-chan context)
                                  (async/close! resume-chan)
                                  (.complete ac)))
                              (onError [this throwable]
                                (async/put! resume-chan (assoc context :io.pedestal.impl.interceptor/error throwable))
                                (async/close! resume-chan))))))
  (write-byte-buffer-body [servlet-response ^ByteBuffer body resume-chan context]
    (let [;; TODO: Not sure we should be calling startAsync here
          ac ^AsyncContext (-> context :servlet-request .startAsync)
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
