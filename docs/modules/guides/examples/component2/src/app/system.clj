(ns app.system
  (:require [com.stuartsierra.component :as component]
            [app.components.greeter :as greeter]
            [app.components.handlers :as handlers]
            app.routes
            app.pedestal))

(defn new-system
  []
  (component/system-map
    :greeter
    (greeter/new-greeter)

    :handler/get-greeting
    (component/using
      (handlers/new-get-greeting)
      [:greeter])

    :route-source
    (component/using
      {}                                                    ;; <1>
      {:get-greeting :handler/get-greeting})                ;; <2>

    :pedestal
    (component/using
      (app.pedestal/new-pedestal)
      [:route-source])))


