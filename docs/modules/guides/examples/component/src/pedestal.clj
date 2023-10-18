;; tag::ns[]
(ns pedestal                                                ;; <1>
  (:require [com.stuartsierra.component :as component]      ;; <2>
            [io.pedestal.http :as http]))                   ;; <3>
;; end::ns[]
;; tag::test?[]
(defn test?
  [service-map]
  (= :test (:env service-map)))
;; end::test?[]
;; tag::component-init[]
(defrecord Pedestal [service-map                            ;; <1>
                     service]
  component/Lifecycle                                       ;; <2>
  ;; end::component-init[]
  ;; tag::component-start[]
  (start [this]
    (if service                                             ;; <1>
      this
      (assoc this :service                                  ;; <2>
             (cond-> (http/create-server service-map)       ;; <3>
               (not (test? service-map)) http/start))))     ;; <4>
  ;; end::component-start[]

  ;; tag::component-stop[]
  (stop [this]
    (when (and service (not (test? service-map)))           ;; <1>
      (http/stop service))
    (assoc this :service nil)))                             ;; <2>
;; end::component-stop[]

;; tag::constructor[]
(defn new-pedestal
  []
  (map->Pedestal {}))
;; end::constructor[]
