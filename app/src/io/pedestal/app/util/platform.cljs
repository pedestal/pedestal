; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.util.platform
  (:require [cljs.reader :as reader]))

(defn safe-read-string [s]
  (reader/read-string s))

(defn parse-int [s]
  (assert (or (number? s) (string? s))
          "the value passed to parse-int must be a number or a string")
  (js/parseInt s 10))

(defn date []
  (js/Date.))

(defn create-timeout [msecs f]
  (js/setTimeout f msecs))

(defn cancel-timeout [timeout]
  (js/clearTimeout timeout))

(defn read-form-if-string [x]
  (if (string? x)
    (try (safe-read-string x)
         (catch js/Error _ nil))
    x))

(defn log-group [group-name coll]
  (.group js/console group-name)
  (doseq [d coll]
    (.log js/console (pr-str d)))
  (.groupEnd js/console))

(defn log-exceptions [f & args]
  (try (apply f args)
       (catch js/Error e
         (.groupCollapsed js/console "Caught exception" e)
         (.log js/console "Was applying function\n" f)
         (.log js/console "With arguments" (pr-str args))
         (.log js/console "Re-throwing error...")
         (.groupEnd js/console)
         (throw e))))
