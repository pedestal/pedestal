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
  (:require [cljs.reader :as reader]
            [goog.net.Cookies :as cookies]))

(defn safe-read-string [s]
  (reader/read-string s))

(defn parse-int [s]
  (assert (or (number? s) (string? s))
          "the value passed to parse-int must be a number or a string")
  (js/parseInt s 10))

(defn date []
  (js/Date.))

(defn create-timeout [msecs f]
  (.setTimeout js/window f msecs))

(defn cancel-timeout [timeout]
  (.clearTimeout js/window timeout))

(defn read-form-if-string [x]
  (if (string? x)
    (try (safe-read-string x)
         (catch js/Error _ nil))
    x))

(defn get-cookie [cookie]
  (.get (goog.net.Cookies. js/document) cookie))

(defn log-group [pre post coll]
  (.log js/console "\n")
  (.log js/console pre)
  (doseq [d coll]
    (.log js/console (pr-str d)))
  (.log js/console post)
  (.log js/console "\n"))
