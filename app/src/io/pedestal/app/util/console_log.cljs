; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.util.console-log)

(defn log-map [m]
  (let [d (assoc m :timestamp (.getTime (js/Date.)))]
    (.log js/console (pr-str d))))

(defn log [& args]
  (log-map (apply hash-map args)))
