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

(ns io.pedestal.http.container
  "Deeper Pedestal<->Container integration and cooperation for HTTP handling")

;; Below are protocols and functions used to tune, tweak, and enhance Pedestal
;; with Container/Servlet Engine specific hooks.
;; The most common case is to extend integration beyond the Servlet Spec -
;; for example, integrating and utilizing NIO throughout the entire stack.
(defprotocol WriteNIOByteBody
  (write-byte-channel-body [servlet-response body resume-chan context])
  (write-byte-buffer-body [servlet-response body resume-chan context]))

