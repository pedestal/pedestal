(ns hello-ssl
  (:require [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]))

(defn greet-handler [request]
  {:status 200
   :body   (format "Hello world from server %s using scheme %s!" (:server-name request) (:scheme request))})   ;; <1>

(def routes #{["/" :get greet-handler :route-name :greet]})

(defn create-connector []
  (-> (conn/default-connector-map 8080)                                                                        ;; <2>
      (assoc :container-options {:ssl?                    true                                                 ;; <3>
                                 :ssl-port                8443                                                 ;; <4>
                                 :keystore                "server.p12"                                         ;; <5>
                                 :key-password            "your-keystore-password-here"                        ;; <6>
                                 :sni-host-check?         false                                                ;; <7>
                                 :keystore-scal-interval  60})                                                 ;; <8>
      (conn/with-default-interceptors)
      (conn/with-routes routes)
      (jetty/create-connector nil)))

(defn start []
  (conn/start! (create-connector)))
