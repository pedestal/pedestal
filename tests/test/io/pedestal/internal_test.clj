; Copyright 2024-2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.internal-test
  (:require [clojure.string :as string]
            [io.pedestal.test-common :as tc]
            [io.pedestal.internal :refer [read-config]]
            [clojure.test :refer [deftest is use-fixtures]])
  (:import (java.io StringWriter)))

(def test-overridden ::test-value)
(def prod-not-overridden ::prod-value)
(def via-string-key ::string-key-value)
(def via-default ::via-default)

(use-fixtures :once
              tc/no-ansi-fixture)

(defmacro capture-output [stream & body]
  `(let [s# (StringWriter.)]
     (with-bindings* {#'~stream s#}
       (fn []
         ~@body))
     (-> s# .toString string/trim)))


(deftest test-overrides-prod
  (is (= ::test-value
         (read-config "overridden" "_XXX_"))))

(deftest value-from-prod-when-not-overridden
  (is (= ::prod-value
         (read-config "not-overridden" "_XXX_"))))

(deftest string-value-is-converted-to-symbol-and-resolved
  (is (= ::string-key-value
         (read-config "string" "_XXX_"))))

(deftest config-value-may-not-be-unqualified-symbol
  (is (thrown-with-msg? IllegalArgumentException #"\Qjust-a-symbol is not a string or qualified symbol\E"
                        (read-config "just-symbol" "_XXX_"))))

(deftest config-value-must-be-string-or-qualified-symbol
  (is (thrown-with-msg? IllegalArgumentException #"\Q42 is not a string or qualified symbol\E"
                        (read-config "not-a-symbol" "_XXX_"))))

(deftest valid-var-from-config-default
  (is (= ::via-default
         (read-config "_XXX_" "_XXX_" :default-value "io.pedestal.internal-test/via-default"))))


(deftest warning-when-symbol-does-not-exist
  (is (= "WARNING: Symbol io.pedestal.internal-test/does-not-exist (default value) does not exist"
         (capture-output *err*
                         (read-config "_XXX_" "_XXX" :default-value "io.pedestal.internal-test/does-not-exist")))))

(deftest error-when-can-not-resolve-symbol
  (is (= (str "ERROR: Could not resolve symbol this.namespace/does-not-exist (from configuration key :does-not-exist): "
              "Could not locate this/namespace__init.class, this/namespace.clj or this/namespace.cljc on classpath.")
         (capture-output *err*
                         (read-config "does-not-exist" "_XXX_")))))

(deftest boolean-config
  (is (= true
         (read-config "_XXX_" "_XXX" :as :boolean :default-value true)))

  (is (= false
         (read-config "_XXX_" "_XXX" :as :boolean :default-value false)))

  (is (= true
         (read-config "_XXX_" "_XXX" :as :boolean :default-value "true")))

  (is (= false
         (read-config "_XXX_" "_XXX" :as :boolean :default-value "false"))))
