(ns ion-provider.ion
  (:require [datomic.ion.lambda.api-gateway :as apig]
            [io.pedestal.http :as http]
            [ion-provider.service :as service]))

(defn handler
  "Ion handler"
  [service-map]
  (-> service-map
      http/default-interceptors
      http/create-provider))

(def app (apig/ionize (handler service/service)))

(comment

 (def h (handler service/service))

 (slurp (:body (h {:server-port    0
                  :server-name    "localhost"
                  :remote-addr    "127.0.0.1"
                  :uri            "/pet/302"
                  :scheme         "http"
                  :request-method :get
                  :headers        {}})))

 (h {:server-port    0
     :server-name    "localhost"
     :remote-addr    "127.0.0.1"
     :uri            "/pets"
     :scheme         "http"
     :request-method :post
     :headers        {"content-type" "application/json"}
     :body           (clojure.java.io/input-stream (.getBytes "{\"id\": 302, \"name\": \"Foob\", \"tag\": \"bird\"}"))})

 (slurp (:body (h {:server-port    0
                   :server-name    "localhost"
                   :remote-addr    "127.0.0.1"
                   :uri            "/pet/302"
                   :scheme         "http"
                   :request-method :put
                   :headers        {"content-type" "application/json"}
                   :body           (clojure.java.io/input-stream (.getBytes "{\"id\": 302, \"name\": \"Foox\", \"tag\": \"bird\"}"))})))

 (h {:server-port    0
     :server-name    "localhost"
     :remote-addr    "127.0.0.1"
     :uri            "/pet/302"
     :scheme         "http"
     :request-method :delete
     :headers        {}})

 )
