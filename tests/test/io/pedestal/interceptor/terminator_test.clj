; Copyright 2024-2025 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor.terminator-test
  "Tests for chain termination logic."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http.response :as response]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.core.async :refer [go >! chan]]
            [io.pedestal.test-common :refer [<!!?]]
            [io.pedestal.http.response :refer [respond-with]])
  (:import (clojure.lang ExceptionInfo)))

(defn- execute [& interceptors]
  (let [context (response/terminate-when-response {})]
    (:response
      (chain/execute context (mapv interceptor interceptors)))))

(def tail
  {:name  :tail
   :enter #(respond-with % 401 {"Content-Type" "text/plain"} "TAIL")})

(deftest fall-through-to-last
  (is (match? {:status 401
               :headers {"Content-Type" "text/plain"}
               :body "TAIL"}
              (execute {:name  :do-nothing
                        :enter identity}
                       tail))))

(deftest valid-response-map
  (is (= {:status 303}
         (execute {:name  :valid
                   :enter #(respond-with % 303)}
                  tail))))

(deftest not-a-map
  ;; It tooks some juggling in interceptor.chain to ensure that an exception
  ;; thrown from the termination check fn was associated with the interceptor
  ;; as shown here.
  (when-let [e (is (thrown-with-msg? ExceptionInfo #"Interceptor attached a :response that is not a map"
                                     (execute {:name  :not-a-map
                                               :enter #(assoc % :response 401)})))]
    (is (match?
          {:exception-type :clojure.lang.ExceptionInfo
           :interceptor    :not-a-map
           :response       401
           :stage          :enter}
          (ex-data e)))))

(deftest invalid-status-key
  ;; It tooks some juggling in interceptor.chain to ensure that an exception
  ;; thrown from the termination check fn was associated with the interceptor
  ;; as shown here.
  (when-let [e (is (thrown-with-msg? ExceptionInfo #"Interceptor attached a :response that is not a map"
                                     (execute {:name  :invalid-status
                                               :enter #(assoc % :response :not-found)})))]
    (is (match?
          {:interceptor    :invalid-status
           :response       :not-found}
          (ex-data e)))))

(deftest async-termination
  (let [ch (chan)]
    (execute
      {:name  :capture
       :leave (fn [context]
                (go
                  (>! ch (:response context))
                  context))}
      {:name  :valid-async
       :enter (fn [context]
                (go
                  (respond-with context 303 "ASYNC")))}
      tail)
    (is (match?
          {:status 303
           :body   "ASYNC"}
          (<!!? ch)))))

(deftest async-termination-invalid-response
  (let [response-ch (chan 1)
        error-ch    (chan 1)]
    (execute
      {:name  :capture
       :leave (fn [context]
                (go
                  (>! response-ch (:response context))
                  context))}
      {:name  :capture-error
       :error (fn [context err]
                (go
                  (>! error-ch err)
                  (respond-with context 599)))}
      {:name  :invalid-async
       :enter (fn [context]
                (go
                  (assoc context :response :invalid)))}
      tail)

    (is (match? {:status 599}
                (<!!? response-ch)))

    (let [e (<!!? error-ch)]
      (is (match? {:interceptor :invalid-async
                   :response    :invalid}
                  (ex-data e))))))
