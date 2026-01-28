; Copyright 2026 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software

(ns io.pedestal.json.charred
  "Implementation of the JSONProcessor protocol for the charred library."
  (:require [charred.api :as charred]
            [io.pedestal.json.protocols :as p])
  (:import (java.io Reader)))

(defn processor
  []
  (reify p/JSONProcessor
    (read-json [_ reader options]
      (charred/read-json reader options))))
