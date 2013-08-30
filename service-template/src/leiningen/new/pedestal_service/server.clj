(ns {{namespace}}.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.service-tools.server :as server]
            [{{namespace}}.service]
            [io.pedestal.service-tools.dev :as dev]))

;; To implement your own server, copy io.pedestal.service-tools.server and
;; modify it.
(defn -main [& args]
  (dev/setup {{namespace}}.service/service #'{{namespace}}.service/routes)
  (apply server/-main args))
