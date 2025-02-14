; Copyright 2025 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.http-kit
  "Support for [Http-Kit](https://github.com/http-kit/http-kit) as a network connector.

  HttpKit provides features similar to the Servlet API, including WebSockets, but does not
  implement any of the underlying interfaces."
  (:require[org.httpkit.server :as hk-server]
           [io.pedestal.interceptor.chain :as chain]))



