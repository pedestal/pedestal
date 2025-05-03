(ns app.routes)                                             ;; <1>

(defrecord RoutesSource [get-greeting])

(defn routes
  [component]
  (let [{:keys [get-greeting]} component]                   ;; <2>
    #{["/api/greet" :get get-greeting]}))                   ;; <3>

(defn new-routes-source
  []
  (map->RoutesSource {}))
