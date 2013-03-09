;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.app.util.platform
  (:require [io.pedestal.app.util.scheduler :as scheduler]
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
