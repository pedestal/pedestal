; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.connector.specs
  (:require [clojure.spec.alpha :as s]
            [io.pedestal.interceptor.specs :as interceptor]))

(s/def ::connector-map (s/keys
                         :req-un [::port ::host ::interceptors ::initial-context ::join?]))

(s/def ::port integer?)
(s/def ::host string?)
;; Note: a collection of interceptors *not* a collect of things that can be converted
;; to interceptors.
(s/def ::interceptors (s/coll-of ::interceptor/interceptor-record
                                 :kind vector?
                                 :into []))
(s/def ::initial-context map?)
(s/def ::join boolean?)

