;; tag::gen-preamble[]
(ns app.components.greeter
  (:require [com.stuartsierra.component :as component]
            [clj-commons.humanize :as h]))                  ;; <1>

(defrecord Greeter [*count]

  component/Lifecycle

  (start [this]
    (assoc this :*count (atom 0)))

  (stop [this]
    (assoc this :*count nil)))

(defn new-greeter
  []
  (map->Greeter {}))
;; end::gen-preamble[]

;; tag::gen1[]
(defn generate-message!                                     ;; <1>
  [component]
  (let [n (-> component :*count (swap! inc))]
    (format "Greeting #%d\n" n)))
;; end::gen1[]

#_;; tag::gen2[]
        (defn generate-message!
          [component]
          (let [n (-> component :*count swap! inc)]
            (format "Greetings for the %s time\n"
                    (h/ordinal n))))
;; end::gen2[]
