(ns ^:shared {{name}}.behavior
    (:require [clojure.string :as string]))

(defn example-model [model-state event]
  (:value event))

(def example-app
  {:models {:example-model {:init "Hello World!" :fn example-model}}})
