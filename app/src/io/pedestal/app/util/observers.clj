; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app.util.observers)

(def ^:private listeners (atom {}))

(defn publish [topic message]
  (doseq [f (get @listeners topic)]
    (f message)))

(defn subscribe [topic f]
  (swap! listeners update-in [topic] (fnil conj []) f))

(defn clear []
  (reset! listeners {}))
