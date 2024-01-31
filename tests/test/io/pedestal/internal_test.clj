; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.internal-test
  (:require [io.pedestal.internal :refer [resolve-var-from]]
            [clojure.test :refer [deftest is]]))

(def test-overridden ::test-value)
(def prod-not-overridden ::prod-value)
(def via-string-key ::string-key-value)

(deftest test-overrides-prod
  (is (= ::test-value
         (resolve-var-from "overridden" "_XXX_"))))

(deftest value-from-prod-when-not-overridden
  (is (= ::prod-value
         (resolve-var-from "not-overridden" "_XXX_"))))

(deftest string-value-is-converted-to-symbol-and-resolved
  (is (= ::string-key-value
         (resolve-var-from "string" "_XXX_"))))

(deftest config-value-may-not-be-unqualified-symbol
  (is (thrown-with-msg? IllegalArgumentException #"\Qjust-a-symbol is not a string or qualified symbol\E"
                        (resolve-var-from "just-symbol" "_XXX_"))))

(deftest config-value-must-be-string-or-qualified-symbol
  (is (thrown-with-msg? IllegalArgumentException #"\Q42 is not a string or qualified symbol\E"
                        (resolve-var-from "not-a-symbol" "_XXX_"))))
