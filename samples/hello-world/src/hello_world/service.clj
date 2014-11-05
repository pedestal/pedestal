(ns hello-world.service
  (:require [io.pedestal.http :as bootstrap]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [ring.util.response :as ring-resp]))

(defn hello-world
  [request]
  (let [name (get-in request [:params :name] "World")]
    (ring-resp/response (str "Hello " name "!\n"))))

(defroutes routes
  [[["/" 
      ["/hello" {:get hello-world}]]]])

(def service {:env :prod
              ::bootstrap/routes routes
              ::bootstrap/resource-path "/public"
              ::bootstrap/type :jetty
              ::bootstrap/port 8080})

