(ns hello-ssl
  (:require [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.log :as log]))

(defn greet-handler [request]
  {:status 200
   :body   (format "Hello world from server %s using scheme %s!" (:server-name request) (:scheme request))})   ;; <1>

(defn redirect-to-secure [secure-port]
  {:name :redirect-to-secure
   :enter (fn [{:keys [request route] :as context}]
            (if (-> request :scheme (= :http))                                                                 ;; <2>
              (let [{:keys [server-name uri query-string]} request
                    redirect-url (format "https://%s:%s%s%s" server-name secure-port uri (if query-string (str "?" query-string) ""))]
                (log/info :in ::redirect-to-secure :route-name (:route-name route) :server-name server-name :redirect-url redirect-url)
                (assoc context :response {:status 302 :headers {"Location" redirect-url}}))                    ;; <3>
              context))})


(def routes #{["/" :get [(redirect-to-secure 8443) greet-handler] :route-name :greet]})

(defn create-connector []
  (-> (conn/default-connector-map 8080)                                                                        ;; <4>
      (assoc :container-options {:ssl?                    true                                                 ;; <5>
                                 :ssl-port                8443                                                 ;; <6>
                                 :keystore                "./server.p12"                                       ;; <7>
                                 :key-password            "your-keystore-password-here"                        ;; <8>
                                 :sni-host-check?         false                                                ;; <9>
                                 :keystore-scal-interval  60})                                                 ;; <10>
      (conn/with-default-interceptors)
      (conn/with-routes routes)
      (jetty/create-connector nil)))

(defn start []
  (conn/start! (create-connector)))
