;; tag::ns[]
(ns app.pedestal
  (:require [com.stuartsierra.component :as component]      ;; <1>
            [io.pedestal.connector :as conn]
            [io.pedestal.http.http-kit :as hk]
            app.routes))                                    ;; <2>
;; end::ns[]
;; tag::inject[]
(defn- inject-components
  [components]
  {:name  ::inject-components
   :enter #(assoc-in % [:request :components] components)})
;; end::inject[]
;; tag::component[]
(defrecord Pedestal [components connector]                  ;; <1>
  component/Lifecycle                                       ;; <2>
  ;; end::component[]
  ;; tag::start[]
  (start [this]
    (assoc this :connector                                  ;; <1>
           (-> (conn/default-connector-map 8890)
               (conn/with-interceptor (inject-components components)) ;; <2>
               (conn/optionally-with-dev-mode-interceptors) ;; <3>
               (conn/with-default-interceptors)
               (conn/with-routes app.routes/routes)         ;; <4>
               (hk/create-connector nil)                    ;;<5>
               (conn/start!))))
  ;; end::start[]

  ;; tag::stop[]
  (stop [this]
    (conn/stop! connector)
    (assoc this :connector nil))
  ;; end::stop[]
  )
;; tag::constructor[]
(defn new-pedestal
  []
  (map->Pedestal {}))
;; end::constructor[]
