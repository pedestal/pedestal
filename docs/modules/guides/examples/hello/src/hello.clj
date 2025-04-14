;; tag::ns[]
(ns hello                                                   ;; <1>
  (:require [io.pedestal.connector :as conn]                ;; <2>
            [io.pedestal.http.http-kit :as hk]))            ;; <3>
;; end::ns[]

;; tag::response[]
(defn greet-handler [_request]                              ;; <1>
  {:status 200
   :body   "Hello, world!\n"})                              ;; <2>
;; end::response[]


;; tag::routing[]
(def routes
  #{["/greet" :get greet-handler :route-name :greet]})
;; end::routing[]

;; tag::connector[]
(defn create-connector []
  (-> (conn/default-connector-map 8890)                     ;; <1>
      (conn/with-default-interceptors)                      ;; <2>
      (conn/with-routes routes)                             ;; <3>
      (hk/create-connector nil)))                           ;; <4>

(defn start []
  (conn/start! (create-connector)))                         ;; <5>
;; end::connector[]
