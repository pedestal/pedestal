(ns hello-world.core
  (:require [io.pedestal.http.route.definition :refer [defroutes]]
            [io.pedestal.http :as http]
            [clojure.core.async :as async]))

(defn hello-world [req]
  (async/go
    (let [c (async/chan 1)
          name (get-in req [:params :name] "World")]
      (async/<! (async/timeout 2000))
      (async/>! c {:status 200
                   :body (str "Hello, " name "!")
                   :headers {}}))))

(defroutes routes
  [[["/"
     ["/hello" {:get hello-world}]]]])

(def service {::http/routes routes
              ::http/port 8080})

(defn -main [& args]
  (-> service
      http/create-server
      http/start))
