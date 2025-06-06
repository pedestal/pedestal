(ns org.example.war.service
  (:require [io.pedestal.connector :as conn]
            [io.pedestal.connector.servlet :as servlet]))

(defn- hello
  [_request]
  {:status 200
   :body   "Greetings from inside the WAR file."})

(defn connection-map
  []
  (-> (conn/default-connector-map -1)
      (conn/with-default-interceptors)
      (conn/with-routes #{["/hello" :get hello]})))

(defn create-bridge
  "Invoked from the Servlet to create the bridge between Servlet API and Pedestal."
  [servlet]
  (servlet/create-bridge servlet (connection-map) nil))
