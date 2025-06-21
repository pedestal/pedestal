(ns org.example.sse.connector
  "Defines the connector configuration for the org.example/sse project."
  (:require [io.pedestal.connector :as connector]
            [io.pedestal.service.resources :as resources]
            [io.pedestal.environment :as env]
            [io.pedestal.connector.dev :as dev]
            [org.example.sse.routes :as routes]))

(defn connector-map
  "Creates a connector map for the org.example/sse service.

  Options:
  - dev-mode: enables dev-interceptors and interceptor logging if true, defaults from
    Pedestal's development mode.
  - join?: if true, then the current thread will block when the connector is started (default is false)."
  [opts]
  (let [{:keys [dev-mode? trace? join?]
         :or {dev-mode? env/dev-mode?
              join? false}} opts]
    (->  (connector/default-connector-map 8080)
         (cond->
           join? (assoc :join? true)
           dev-mode? (connector/with-interceptors dev/dev-interceptors)
           trace? dev/with-interceptor-observer)
         ;; with-default-interceptors is only to be used for initial scaffolding and should be replaced
         ;; with an application-specific series of calls to with-interceptor.
         (connector/with-default-interceptors {:allowed-origins ["localhost"]})
         ;; Routing is generally the last interceptors added.
         ;; This adds application's API routes plus static files stored
         ;; as resources.
         (connector/with-routes
           (routes/routes)
           (resources/resource-routes {:resource-root "public"})))))

