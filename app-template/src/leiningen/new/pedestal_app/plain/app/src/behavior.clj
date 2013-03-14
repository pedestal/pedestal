(ns ^:shared {{name}}.behavior
    (:require [clojure.string :as string]))

(defn example-transform [transform-state message]
  (:value message))

(def example-app
  {:transform {:example-transform {:init "Hello World!" :fn example-transform}}})
