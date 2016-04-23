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

(ns io.pedestal.http.params
  (:require [io.pedestal.interceptor :as interceptor]))

(defn keywordize-keys
  [x]
  (if (map? x)
    (persistent!
     (reduce-kv
      (fn [m k v]
        (assoc! m
                (if (string? k) (keyword k) k)
                (keywordize-keys v)))
      (transient {})
      x))
    x))

(defn keywordize-request-element
  [element context]
  (update-in context [:request element] keywordize-keys))

(def keywordize-request-params (partial keywordize-request-element :params))
(def keywordize-request-body-params (partial keywordize-request-element :body-params))

(def keyword-params
  "Interceptor that converts the :params map to be keyed by keywords"
  (interceptor/-interceptor
   {:name ::keyword-params
    :enter keywordize-request-params}))

(def keyword-body-params
  "Interceptor that converts the :body-params map to be keyed by keywords"
  (interceptor/-interceptor
   {:name ::keyword-body-params
    :enter keywordize-request-body-params}))
