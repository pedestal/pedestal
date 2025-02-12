; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.protocols)

(defprotocol ResponseBufferSize

  "Returns the buffer size of the response object; this protocol is adapted to the HTTPServletResponse object which
  is not a dependency available to the pedestal.service module."

  (response-buffer-size [this]
    "Returns the buffer size in bytes available to this response, or nil if it can not be determined."))

;; Cover the case where the servlet response object is not available.

(extend-protocol ResponseBufferSize

  nil

  (response-buffer-size [_] nil))
