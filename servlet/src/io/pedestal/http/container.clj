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
  "Deeper Pedestal<->Container integration and cooperation for HTTP handling")

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
   [[with-error]], before writing it to the channel."
  (write-byte-channel-body [servlet-response body resume-chan context])
  (write-byte-buffer-body [servlet-response body resume-chan context]))

