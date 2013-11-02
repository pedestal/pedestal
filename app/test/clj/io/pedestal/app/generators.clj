(ns io.pedestal.app.generators
  (:require [simple-check.generators :as gen]))

(defn model [size]
  (if (= size 0)
    (gen/map gen/keyword gen/nat)
    (let [new-size (quot size 2)
          smaller-model (gen/resize new-size (gen/sized model))]
      (gen/map gen/keyword (gen/one-of [gen/nat smaller-model])))))
