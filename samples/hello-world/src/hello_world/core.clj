(ns hello-world.core
  (:require [io.pedestal.http.route.definition :refer [defroutes]]
            [io.pedestal.http :as http]))


(defn hello-world [req]
  (let [name (get-in req [:params :name] "World")]
    {:status 200
     :body (str "Hello, " name "!")
     :headers {}}))

(defroutes routes
  [[["/"
     ["/hello" {:get hello-world}]]]])

(def service {::http/routes routes
              ::http/port 8080})

(defn -main [& args]
  (-> service
      http/create-server
      http/start))
