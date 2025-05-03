(ns app.components.greeter
  (:require [com.stuartsierra.component :as component]
            [clj-commons.humanize :as h]))

(defrecord Greeter [*count]

  component/Lifecycle

  (start [this]
    (assoc this :*count (atom 0)))

  (stop [this]
    (assoc this :*count nil)))

(defn new-greeter
  []
  (map->Greeter {}))

(defn generate-message!
  [component]
  (let [n (-> component :*count (swap! inc))]
    (format "Greetings for the %s time\n"
            (h/ordinal n))))
