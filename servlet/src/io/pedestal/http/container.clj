; Copyright 2024-2025 Nubank NA
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.container
  "Deeper Pedestal<->Container integration and cooperation for HTTP handling"
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [io.pedestal.interceptor.chain :as chain])
  (:import (jakarta.servlet.http HttpServletResponse)
           (java.io OutputStream)
           (java.nio ByteBuffer)
           (java.nio.channels Channels ReadableByteChannel)))

;; Below are protocols and functions used to tune, tweak, and enhance Pedestal
;; with Container/Servlet Engine specific hooks.
;; The most common case is to extend integration beyond the Servlet Spec -
;; for example, integrating and utilizing NIO throughout the entire stack.
(defprotocol WriteNIOByteBody
  "When a response body is a ByteBuffer or a ByteChannel, this protocol is the bridge
   to container-specific code to handle those cases efficiently and asynchronously.

   This is effectively part of the interceptor chain; once the body has been written,
   the provided context should be written to the resume-chan (a core.async channel).

   If an exception occurs, the error should be attached to the context via
   [[with-error]], before writing it to the channel.

   Pedestal extends this protocol to any jakarta.servlet.http.HttpServletResponse, by copying the body to the
   response's output stream on the calling thread (so NIO bodies work in any servlet container, such as when
   deploying a WAR file), and to the response class of its embedded Jetty, which sends the body without copying it.
   Since 1.0."
  (write-byte-channel-body [servlet-response body resume-chan context])
  (write-byte-buffer-body [servlet-response body resume-chan context]))

(defn- write-through-output-stream
  "Calls (write-fn output-stream), flushes the response, and puts the context (with the error attached,
  if any) on resume-chan."
  [^HttpServletResponse servlet-response write-fn resume-chan context]
  (let [context' (try
                   (write-fn (.getOutputStream servlet-response))
                   (.flushBuffer servlet-response)
                   context
                   (catch Throwable t
                     (chain/with-error context t)))]
    (async/put! resume-chan context')
    (async/close! resume-chan)))

(extend-protocol WriteNIOByteBody

  ;; The default for any servlet container; a container class (such as Jetty's) can extend
  ;; the protocol itself, which takes precedence over this interface.
  HttpServletResponse

  (write-byte-channel-body [servlet-response ^ReadableByteChannel body resume-chan context]
    (write-through-output-stream servlet-response
                                 (fn [^OutputStream output-stream]
                                   ;; Closing the input stream closes the channel, even when the copy fails.
                                   (with-open [input-stream (Channels/newInputStream body)]
                                     (io/copy input-stream output-stream :buffer-size 8192)))
                                 resume-chan
                                 context))

  (write-byte-buffer-body [servlet-response ^ByteBuffer body resume-chan context]
    (write-through-output-stream servlet-response
                                 (fn [^OutputStream output-stream]
                                   (let [channel (Channels/newChannel output-stream)]
                                     (while (.hasRemaining body)
                                       (.write channel body))))
                                 resume-chan
                                 context)))

