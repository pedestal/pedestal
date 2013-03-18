(ns ^:shared {{name}}.behavior
    (:require [clojure.string :as string]
              [io.pedestal.app.messages :as msg]))

(defn example-transform [transform-state message]
  (condp = (msg/type message)
    msg/init (:value message)
    transform-state))

(def example-app
  {:transform {:example-transform {:init "Hello World!" :fn example-transform}}})
