; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor.protocols
  "Protocols that can be implemented by components which may then be converted into interceptors."
  {:added "0.8.0"})

(defprotocol Handler
  (handle [_ request]
    "Handles a request map and returns a response map, or a channel that conveys the response map."))

(defprotocol OnEnter
  (enter [_ context]
    "Corresponds to the :enter phase of a standard interceptor; passed the context and returns the (new) context,
    or a core.async channel that will convey the new context."))

(defprotocol OnLeave
  (leave [_ context]
    "Corresponds to the :leave phase of a standard interceptor; passed the context and returns the (new) context,
    or a core.async channel that will convey the new context."))

(defprotocol OnError
  (error [_ context exception]
    "Corresponds to the :error phase of a standard interceptor; passed the context and an exception, and returns the (new) context,
    or a core.async channel that will convey the new context."))
