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
           (ideal-change-report old-model new-model)))))

(defspec assoc-model-tests
  50
  (prop/for-all [m (gen/sized pgen/model)
                 path (gen/such-that not-empty (gen/vector gen/keyword))
                 k gen/keyword
                 i (gen/one-of [gen/int (gen/sized pgen/model)])]
                (assoc-ok m path k i)))

(comment
  (gen/sample (gen/sized model))
  )
