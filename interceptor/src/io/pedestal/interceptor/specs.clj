; Copyright 2024 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor.specs
  (:require [clojure.spec.alpha :as s]
            [io.pedestal.interceptor :as i]))

;; Things that can be converted into interceptors:
(s/def ::interceptor #(satisfies? i/IntoInterceptor %))
(s/def ::interceptors (s/coll-of ::interceptor))

;; Things that are actually interceptors:
(s/def ::interceptor-record i/interceptor?)

(s/fdef i/interceptor
        :args (s/cat :input-object ::interceptor)
        :ret ::interceptor-record)
