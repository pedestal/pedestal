(ns front-page
  (:require [io.pedestal.connector :as conn]
            [io.pedestal.http.http-kit :as hk]))

(defn greet-handler
  [_request]
  {:status 200
   :body   "Hello, world!"})

(defn start
  []
  (-> (conn/default-connector-map 8080)
      (assoc :join? true)
      (conn/with-default-interceptors)
      (conn/with-routes #{["/greet" :get greet-handler]})
      (hk/create-connector nil)
      (conn/start!)))
