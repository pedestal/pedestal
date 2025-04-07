; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.connector-test
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.connector :as connector]
            [io.pedestal.interceptor :as interceptor]))

(deftest nil-interceptor-is-ignored
  (is (= []
         (-> (connector/default-connector-map 0)
             (connector/with-interceptor nil)
             :interceptors))))

(defn my-handler [_request] ::response)

(deftest interceptors-are-converted-as-added
  (let [interceptors (-> (connector/default-connector-map 0)
                         (connector/with-interceptor my-handler)
                         :interceptors)]
    (is (seq interceptors))
    (is (= true
           (every? interceptor/interceptor? interceptors)))))
