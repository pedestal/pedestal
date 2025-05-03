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
      (app.routes/new-routes-source)
      {:get-greeting :handler/get-greeting})                ;; <1>

    :pedestal
    (component/using
      (app.pedestal/new-pedestal)
      [:route-source])))


