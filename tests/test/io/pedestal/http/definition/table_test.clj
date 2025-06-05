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
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http.route.definition.table :refer [table-routes]])
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


(comment

  ;; This is used to verify the content in routing-quick-reference.adoc

  (:routes
    (io.pedestal.http.route/expand-routes
      #{#_ {:app-name :example-app
         :scheme   :https
         :host     "example.com"}
        ["/department/:id/employees" :get [identity]
         :route-name :org.example.app/employee-search
         :constraints {:name  #".+"
                       :order #"(asc|desc)"}]}))

  )
