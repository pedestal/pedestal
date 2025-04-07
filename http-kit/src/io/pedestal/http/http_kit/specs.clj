; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.http-kit.specs
  (:require [clojure.spec.alpha :as s]
            [io.pedestal.http.http-kit :as http-kit]
            [io.pedestal.connector.specs :as connector]))

(s/def ::container-options (s/nilable map?))

(s/fdef http-kit/create-connector
        :args (s/cat :connector-map ::connector/connector-map
                     :options ::container-options))

