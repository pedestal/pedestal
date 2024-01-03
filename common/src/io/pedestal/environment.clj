; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.environment
  "Information about the running environment for the Pedestal application."
  {:added "0.7.0"})

(def dev-mode?
  "Set to the boolean value of the system property `io.pedestal.dev-mode`.

  Development mode exists to assist in setting up a useful REPL-oriented workflow for local development and testing.
  It should never be enabled in production."
  (Boolean/getBoolean "io.pedestal.dev-mode"))
