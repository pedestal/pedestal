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
  "Extends Pedestal protocols onto Jetty container classes.

  There is no reason to directly require this namespace."
  {:deprecated "Deprecated in 0.8.0, will be made internal."}
  (:require [io.pedestal.http.container :as container]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.core.async :as async])
  (:import (java.nio.channels ReadableByteChannel)
           (java.nio ByteBuffer)
           (org.eclipse.jetty.ee10.servlet ServletApiResponse)
           (org.eclipse.jetty.util Callback)))

(defn- continue
  ([ch context]
   (async/put! ch context)
   (async/close! ch))
  ([ch context t]
   (continue ch (chain/with-error context t))))

(extend-protocol container/WriteNIOByteBody

  ServletApiResponse

  (write-byte-channel-body [servlet-api-response ^ReadableByteChannel body resume-chan context]
    (.sendContent (-> servlet-api-response .getServletChannel .getHttpOutput)
                  body
                  (reify Callback
                    (succeeded [_]
                      (.close body)
                      (continue resume-chan context))
                    (failed [_ throwable]
                      (.close body)
                      (continue resume-chan context throwable)))))

  (write-byte-buffer-body [servlet-api-response ^ByteBuffer body resume-chan context]
    (.sendContent (-> servlet-api-response .getServletChannel .getHttpOutput)
                  body
                  (reify Callback
                    (succeeded [_]
                      (continue resume-chan context))
                    (failed [_ throwable]
                      (continue resume-chan context throwable))))))

