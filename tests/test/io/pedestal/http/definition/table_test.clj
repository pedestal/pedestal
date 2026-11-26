; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.definition.table-test
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.http.route.definition.table :refer [table-routes]]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.quoted-routes-shim :as shim])
  (:import (java.lang AssertionError)))

(deftest can-support-extra-key-value-pairs
  (is (match?
        {:routes [{::this  :that
                   ::other :other}]}
        (table-routes [["/api/foo" :get identity :route-name :one
                        ::this :that ::other :other]]))))


(deftest missing-value-for-extra-kv-on-table-route

  (is (thrown-with-msg?
        AssertionError
    #".*\Qthere were unused elements (:io.pedestal.http.definition.table-test/missing)\E.*"
        (table-routes [["/api/foo" :get identity :route-name :one
                        ::missing]]))))

(deftest conflicting-keys-are-asserted
  (is (thrown-with-msg?
        AssertionError
        #".*\Qadditional key :path conflicts with standard route key\E.*"
        (table-routes [["/api/foo" :get identity :path :conflict]]))))


(defn handler
  [_request]
  {:status 200})

(defn ^:private route-names
  [route-spec]
  (->> (table-routes route-spec)
       :routes
       (mapv :route-name)))

(deftest route-name-from-a-var-handler
  ;; Without default handler names, the interceptor created from the handler has only a derived name,
  ;; which route names ignore: the route name comes from the Var, as it did from the quoted symbol
  ;; (and as in terse routes).
  (doseq [default-handler-names? [true false]]
    (testing (str "with *default-handler-names* " default-handler-names?)
      (binding [interceptor/*default-handler-names* default-handler-names?]
        (is (= [::handler] (route-names #{["/t" :get #'handler]})))
        (is (= [::handler] (route-names #{["/t" :get [#'handler]]})))
        (is (= [::handler]
               (shim/with-shim
                 (route-names `#{["/t" :get handler]})))
            "the same route name as the symbol, under a compatibility shim")))))
