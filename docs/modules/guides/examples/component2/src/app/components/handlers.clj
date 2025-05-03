(ns app.components.handlers
  (:require [io.pedestal.interceptor.component :as c]
            [app.components.greeter :as greeter]))

(c/definterceptor get-greeting                              ;; <1>
  [greeter]                                                 ;; <2>
  
  (handle [_ _request]                                      ;; <3>
    {:status 200
     :body   (greeter/generate-message! greeter)}))         ;; <4>

(defn new-get-greeting                                      ;; <5>
  []
  (map->get-greeting {}))
