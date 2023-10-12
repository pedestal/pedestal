;; tag::ns[]
(ns hello                                        ;; <1>
  (:require [io.pedestal.http :as http]          ;; <2>
            [io.pedestal.http.route :as route])) ;; <3>
;; end::ns[]

;; tag::response[]
(defn respond-hello [request]          ;; <1>
  {:status 200 :body "Hello, world!"}) ;; <2>
;; end::response[]


;; tag::routing[]
(def routes
  (route/expand-routes                                   ;; <1>
   #{["/greet" :get respond-hello :route-name :greet]})) ;; <2>
;; end::routing[]

;; tag::server[]
(defn create-server []
  (http/create-server     ;; <1>
   {::http/routes routes  ;; <2>
    ::http/type   :jetty  ;; <3>
    ::http/port   8890})) ;; <4>

(defn start []
  (http/start (create-server))) ;; <5>
;; end::server[]
