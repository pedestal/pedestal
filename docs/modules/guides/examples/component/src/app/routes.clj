;; tag::response[]
(ns app.routes
  (:require [app.components.greeter :as greeter]))          ;; <1>

(defn get-greeting [request]
  (let [greeter (get-in request [:components :greeter])]    ;; <2>
    {:status 200
     :body   (greeter/generate-message! greeter)}))
;; end::response[]

;; tag::routes[]
(def routes
  #{["/greet" :get get-greeting :route-name :greet]})
;; end::routes[]

;; tag::routes2[]
(def routes
  #{["/api/greet" :get get-greeting :route-name :greet]})
;; end::routes2[]
