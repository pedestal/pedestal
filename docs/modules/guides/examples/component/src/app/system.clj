;; tag::ns[]
(ns app.system
  (:require [com.stuartsierra.component :as component]
            [app.components.greeter :as greeter]            ;; <1>
            app.pedestal))

;; tag::app[]
(defn new-system
  []                                                        ;; <1>
  (component/system-map
    :greeter
    (greeter/new-greeter)

    :components
    (component/using {} [:greeter])

    :pedestal                                               ;; <5>
    (component/using                                        ;; <6>
      (app.pedestal/new-pedestal)
      [:components])))
;; end::app[]


