; Copyright 2023 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor.path-params-decoder-test
  (:require [clojure.test :refer [deftest is]]
            matcher-combinators.clj-test
            [io.pedestal.http.route :refer [path-params-decoder]]))

(defn- make-context [path-params]
  {:request
   {:path-params path-params}})

(defn- attempt [context]
  ((:enter path-params-decoder) context))

(deftest parse-params-success
  (is (match?
        {:request {:path-params {:hello "Hello World"
                                 :offer "50% Off"}}}
        (attempt (make-context {:hello "Hello+World"
                                :offer "50%25+Off"})))))

(deftest parse-params-failure
  (is (match?
        {:response {:status 400
                    :body   "Bad Request - URLDecoder: Incomplete trailing escape (%) pattern"}}
        (attempt (make-context {:correct   "Hello+World"
                                :incorrect "Trailing %"})))))


(deftest parse-is-idempotent
  (let [output-context (attempt (make-context {:offer "50%25+Off"}))]
    (is (match?
          {:request {:path-params {:offer "50% Off"}}}
          output-context))
    (is (identical? output-context
                    (attempt output-context)))))
