(ns {{namespace}}.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.service-tools.server :as server]
            [{{namespace}}.service]
            [io.pedestal.service-tools.dev :as dev]))

(defn run-dev
  "The entry-point for 'lein run-dev'"
  [& args]
  (dev/init {{namespace}}.service/service #'{{namespace}}.service/routes)
  (apply dev/-main args))

;; To implement your own server, copy io.pedestal.service-tools.server and
;; modify it.
(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (server/init {{namespace}}.service/service)
  (apply server/-main args))
