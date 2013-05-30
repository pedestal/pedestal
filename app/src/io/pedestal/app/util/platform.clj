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
  (:require [io.pedestal.app.util.scheduler :as scheduler]
            [io.pedestal.app.util.log :as log]
            clojure.edn)
  (:import java.util.UUID))

(def session-id (.toString (java.util.UUID/randomUUID)))

(defn safe-read-string [s]
  (clojure.edn/read-string s))

(defn parse-int [s]
  (assert (or (number? s) (string? s))
          "the value passed to parse-int must be a number or a string")
  (cond (number? s)
        (long s)
        (string? s)
        (Long/parseLong s)))

(defn date []
  (java.util.Date.))

(def scheduler (scheduler/scheduler))

(defn create-timeout [msecs f]
  (scheduler/schedule scheduler msecs f))

(defn cancel-timeout [timeout]
  (scheduler/cancel timeout))

(defn read-form-if-string [x]
  (if (string? x)
    (try (safe-read-string x)
         (catch Throwable _ nil))
    x))

(defmulti get-cookie identity)

(defmethod get-cookie :default [cookie]
  session-id)

(defn log-group [pre post coll]
  (println "\n")
  (println pre)
  (doseq [d coll]
    (println (pr-str d)))
  (println post)
  (println "\n"))

(defn log-exceptions [f & args]
  (try (apply f args)
       (catch Throwable e (log/error :exception e))))
