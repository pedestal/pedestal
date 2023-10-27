; Copyright 2023 NuBank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns  ^:no-doc io.pedestal.internal
  "Internal utilities, not for reuse, subject to change at any time.")

(defn vec-conj
  [v value]
  (conj (or v []) value))

(defn into-vec
  [v coll]
  (into (or v []) coll))
