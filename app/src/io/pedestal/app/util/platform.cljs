;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

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
