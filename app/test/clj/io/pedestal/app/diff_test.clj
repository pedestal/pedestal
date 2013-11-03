; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.diff-test
  (:require [clojure.test :refer :all]
            [simple-check.core :as sc]
            [simple-check.generators :as gen]
            [simple-check.properties :as prop]
            [simple-check.clojure-test :as ct :refer (defspec)]
            [io.pedestal.app.generators :as pgen]
            [io.pedestal.app.diff :refer :all]
            [io.pedestal.app.helpers :refer :all]))

(defn assoc-ok [old-model path k i]
  (or (not (map? (get-in old-model path))) ;; this would be a user error
      (let [new-model (update-in old-model path assoc k i)]
        (= (set (model-diff-inform [path] old-model new-model))
           (set (model-diff-inform old-model new-model))
           (ideal-change-report old-model new-model)))))

(defspec assoc-model-tests
  50
  (prop/for-all [m (gen/sized pgen/model)
                 path (gen/such-that not-empty (gen/vector gen/keyword))
                 k gen/keyword
                 i (gen/one-of [gen/int (gen/sized pgen/model)])]
                (assoc-ok m path k i)))
