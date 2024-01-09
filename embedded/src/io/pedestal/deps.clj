; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:no-doc io.pedestal.deps)

(defn data-fn
  "Example data-fn handler.

  Result is merged onto existing options data."
  [_data]
  ;; returning nil means no changes to options data
  nil)

(defn template-fn
  "Example template-fn handler.

  Result is used as the EDN for the template."
  [edn _data]
  ;; must return the whole EDN hash map
  edn)

(defn post-process-fn
  "Example post-process-fn handler.

  Can programmatically modify files in the generated project."
  [_edn _data])
