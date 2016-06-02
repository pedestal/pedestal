; Copyright 2013 Relevance, Inc.
; Copyright 2014-2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.path
  (:require [clojure.string :as str])
  (:import (java.util.regex Pattern)))

;;; Parsing pattern strings to match URI paths

(defn- parse-path-token [out string]
  (condp re-matches string
    #"^:(.+)$" :>> (fn [[_ token]]
                     (let [key (keyword token)]
                       (-> out
                           (update-in [:path-parts] conj key)
                           (update-in [:path-params] conj key)
                           (assoc-in [:path-constraints key] "([^/]+)"))))
    #"^\*(.+)$" :>> (fn [[_ token]]
                      (let [key (keyword token)]
                        (-> out
                            (update-in [:path-parts] conj key)
                            (update-in [:path-params] conj key)
                            (assoc-in [:path-constraints key] "(.*)"))))
    (update-in out [:path-parts] conj string)))

(defn parse-path
  ([pattern] (parse-path {:path-parts [] :path-params [] :path-constraints {}} pattern))
  ([accumulated-info pattern]
     (if-let [m (re-matches #"/(.*)" pattern)]
       (let [[_ path] m]
         (reduce parse-path-token
                 accumulated-info
                 (str/split path #"/")))
       (throw (ex-info "Routes must start from the root, so they must begin with a '/'" {:pattern pattern})))))

(defn path-regex [{:keys [path-parts path-constraints] :as route}]
  (let [[pp & pps] path-parts
        path-parts (if (and (seq pps) (string? pp) (empty? pp)) pps path-parts)]
    (re-pattern
     (apply str
      (interleave (repeat "/")
                  (map #(or (get path-constraints %) (Pattern/quote %))
                       path-parts))))))

(defn merge-path-regex [route]
  (assoc route :path-re (path-regex route)))
