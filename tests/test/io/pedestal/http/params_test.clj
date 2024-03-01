; Copyright 2024 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.params-test
  (:require [io.pedestal.http.params :as params]
            [clojure.test :refer [deftest is are]]))

(defn valid-interceptor? [interceptor]
  (and (every? fn? (remove nil? (vals (select-keys interceptor [:enter :leave :error]))))
       (or (nil? (:name interceptor)) (keyword? (:name interceptor)))
       (some #{:enter :leave} (keys interceptor))))

(defn app [{:keys [response request] :as context}]
  (assoc context :response (or response (merge request {:status 200 :body "OK"}))))

(defn context [req]
  {:request (merge {:headers {}  :request-method :get} req)})

(deftest keywordization
  (are [v e] (= e (params/keywordize-keys v))
    "xyz"                 "xyz"
    "io.pedestal.http/kw" "io.pedestal.http/kw"
    "not a keyword"       "not a keyword"
    "2nu"                 "2nu"
    {"a" 1 "b" 2}         {:a 1 :b 2}
    {"a" {"b" 1}}         {:a {:b 1}}
    {"a" {"b" "val"}}     {:a {:b "val"}}))

(deftest keyword-params-is-valid
  (is (valid-interceptor? params/keyword-params))
  (is (= {:a "1" :b "2"}
         (->
          (context {:params {"a" "1" "b" "2"}})
          ((:enter params/keyword-params))
          app
          (get-in [:request :params])))))

(deftest keyword-body-params-is-valid
  (is (valid-interceptor? params/keyword-body-params))
  (is (= {:a "1" :b "2"}
         (->
          (context {:body-params {"a" "1" "b" "2"}})
          ((:enter params/keyword-body-params))
          app
          (get-in [:request :body-params])))))
