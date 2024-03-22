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

(ns io.pedestal.websocket.specs
  (:require [clojure.spec.alpha :as s]))

(s/def ::endpoint-map (s/keys :opt-un [::on-open
                                       ::on-close
                                       ::on-error
                                       ::on-text
                                       ::on-binary]))

;; TODO: Expand these as fspec's
(s/def ::on-open fn?)
(s/def ::on-close fn?)
(s/def ::on-error fn?)
(s/def ::on-text fn?)
(s/def ::on-binary fn?)

(s/def ::websockets-map
  (s/map-of string? ::endpoint-map))
