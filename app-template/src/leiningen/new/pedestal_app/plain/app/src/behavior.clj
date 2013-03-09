(ns ^:shared {{name}}.behavior
    (:require [clojure.string :as string]))

(defn example-model [model-state message]
  (:value message))

(def example-app
  {:models {:example-model {:init "Hello World!" :fn example-model}}})
