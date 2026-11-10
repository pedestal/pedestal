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
            [io.pedestal.internal :as i :refer [read-config]]
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

(deftest keyword-config
  (is (= nil
         (read-config "_XXX_" "_XXX_" :as :keyword)))

  (is (= ::default
         (read-config "_XXX_" "_XXX_" :as :keyword :default-value ::default)))

  (is (= :converted
         (read-config "_XXX_" "_XXX_" :as :keyword :default-value "converted"))))

(defn- deprecation-warning
  "Returns the warning printed by the body, without its call stack."
  [f]
  (binding [i/*suppress?* false]
    (i/reset-deprecations)
    (let [output (capture-output *err* (f))]
      (string/replace output #"(?s)\nCall stack: .*" ""))))

(deftest deprecation-warning-for-pedestal-1-0
  (is (= (str "WARNING: io.pedestal.internal-test/old-fn was deprecated in version 0.8.0 and will be removed in Pedestal 1.0\n"
              "Use io.pedestal.internal-test/new-fn instead.\n"
              "See https://pedestal.io/pedestal/0.8/deprecations.html#an-anchor")
         (deprecation-warning #(i/deprecated `old-fn
                                 :in "0.8.0"
                                 :removed-in "1.0"
                                 :replacement "Use io.pedestal.internal-test/new-fn instead."
                                 :docs "an-anchor")))))

(deftest deprecation-warning-with-outcome
  (is (= (str "WARNING: an old feature was deprecated in version 0.8.0; in Pedestal 1.0, it changes\n"
              "Use the new feature.")
         (deprecation-warning #(i/deprecated ::old-feature
                                 :in "0.8.0"
                                 :noun "an old feature"
                                 :outcome "; in Pedestal 1.0, it changes"
                                 :replacement "Use the new feature.")))))

(deftest deprecation-warning-without-options-is-unchanged
  ;; Other libraries use this internal macro for their own deprecations, and code compiled
  ;; against an earlier release calls the 3-argument deprecated*.
  (is (= "WARNING: io.pedestal.internal-test/other-fn was deprecated in version 1.2 and may be removed in a future release"
         (deprecation-warning #(i/deprecated `other-fn :in "1.2"))))
  (is (= "WARNING: something has been deprecated and may be removed in a future release"
         (deprecation-warning #(i/deprecated* ::something nil "something")))))

(deftest deprecation-warning-is-printed-once-per-label
  (binding [i/*suppress?* false]
    (i/reset-deprecations)
    (let [warn #(i/deprecated ::once :replacement "Use something else.")]
      (is (string/includes? (capture-output *err* (warn)) "Use something else."))
      (is (= "" (capture-output *err* (warn)))))))
