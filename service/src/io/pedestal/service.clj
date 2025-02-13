; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service
  "Replacement for io.pedestal.http that is not (directly) linked to the Jakarta Servlet API and is generally
   simpler to use."
  {:added "0.8.0"}
  (:require [io.pedestal.http.cors :as cors]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.tracing :as tracing]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]
            [io.pedestal.service.protocols :as p]
            io.pedestal.http.body-params
            io.pedestal.http.csrf
            io.pedestal.http.secure-headers
            [io.pedestal.service.interceptors :as interceptors]))

(defn default-service-map
  "Creates a default service map for the given port and optional host.  host defaults to \"localhost\"
  which is good for local testing (accessible only from the local host),
  but \"0.0.0.0\" (accessible from any TCP/IP connection) is a better option when deployed."
  ([port]
   (default-service-map "localhost" port))
  ([host port]
   {:port            port
    :host            host
    :interceptors    []
    :initial-context {}
    :join?           false}))

(defn with-interceptor
  "Appends to the :interceptors in the service map, or does nothing if interceptor is nil.

  interceptor must be an interceptor record.
  "
  [service-map interceptor]
  {:pre [(or (nil? interceptor)
             (interceptor/interceptor? interceptor))]}
  (if interceptor
    (update service-map :interceptors conj interceptor)
    service-map))

(defn with-interceptors
  "Appends a sequence of interceptors using [[with-interceptor]]."
  [service-map interceptors]
  (reduce with-interceptor service-map interceptors))

(defmacro with-routing
  "A macro for adding a routing interceptor (and an interceptor to decode
  path parameters) to the service map.
  This is generally the last step in building the interceptor chain.

  This is a wrapper around the [[routes-from]] macro, which helps with
  developing at the REPL.

  The router-constructor is a function that is passed the expanded routes and returns
  a routing interceptor.  It may also be one of :sawtooth, :map-tree, :prefix-tree,
  or :linear-search (the four built-in router constructors). :sawtooth is
  a good default for new applications especially.

  The provided route-fragments must extend the [[ExpandableRoutes]] protocol; these will
  either by [[RoutingFragments]] (from directly invoking a function such as
  [[table-routes]]) or a data structure (vector, map, or set) that can be explicitly
  converted to a RoutingFragment.

  At least one route fragment is required.

  Evalulates to the service map with a routing interceptor added to the :interceptor key.

  In addition, a default interceptor to decode path parameters is added. "
  [service-map router-constructor & route-fragments]
  `(with-interceptors ~service-map
                      [(route/router (route/routes-from ~@route-fragments)
                                     ~router-constructor)
                       route/path-params-decoder]))

(defn with-default-interceptors
  "Sets up a default set of interceptors for _early_ development of an application.

  **It is expected that an application under active development will not use this function, but
  will provide its own interceptor stack explicitly.**

  These interceptors provide basic secure functionality with a limited amount of configurability.
  Many of the underlying interceptors can be configured for greater security.

  A routing interceptor should be added after all other interceptors.

  Interceptors (in order):

  Role                       | Description                                   | Provided By
  ----                       |---                                            |---
  Request tracing            | Make request observable via Open Telemetry    | [[request-tracing-interceptor]]
  Request logging            | Log incoming request method and URI           | [[log-request]]
  Allowed origins (optional) | Only allow requests from specified origins    | [[allow-origin]]
  Session support (optional) | Persist data between requests                 | [[session]]
  Body params                | Parse JSON, EDN, etc. body into appropriate  parameters (:edn-body, :json-body, etc.) | [[body-params]]
  Cross site request forgery | Detect forged requests                        | [[anti-forgery]]
  Default content type       | Set response content type from request file extension, if not otherwise set | [[content-type]]
  Query parameters           | Decode request :query-string to :query-params | [[query-params]]
  Secure headers             | Ensures a number of security-related headers  | [[secure-headers]]

  Note that application code *should not* depend on the exact names of the interceptors, as those may be subject
  to change.

  Option            | Notes
  ------            |---
  :allowed-origins  | Passed to [[allow-origin]]
  :session-options  | If non-nil, passed to [[session]]
  :extra-mime-types | Passed to [[content-type]]"
  [service-map & {:as options}]
  (let [{:keys [allowed-origins
                session-options
                extra-mime-types]} options]
    (with-interceptors service-map
                       [(tracing/request-tracing-interceptor)
                        interceptors/log-request
                        (when allowed-origins
                          (cors/allow-origin allowed-origins))
                        (when session-options
                          (ring-middlewares/session session-options))
                        (ring-middlewares/content-type {:mime-types extra-mime-types})
                        route/query-params
                        (io.pedestal.http.body-params/body-params)
                        (io.pedestal.http.csrf/anti-forgery)
                        (io.pedestal.http.secure-headers/secure-headers)
                        route/query-params])))

(defn with-file-access
  "Adds an interceptor exposing access to files on the file system, routed at file-path; this uses
  [[file]]. The URI `/` maps to the contents of the directory at `file-path`.

  This should be called just before adding a routing interceptor.

  This is an alternative to [[file-routes]], and should only be used when file routing would conflict
  with other routes."
  [service-map file-path]
  (with-interceptor service-map (ring-middlewares/file file-path)))

(defn with-resource-access
  "Adds an interceptor exposing access to resources on the classpath system, routed at root-path; this uses
  [[file]]. The URI `/` maps to the contents of the classpath with a prefix of `root-path`.

  This should be called just before adding a routing interceptor.

  This is an alternative to [[resource-routes]], and should only be used when resource routing would conflict
  with other routes."
  [service-map root-path]
  (with-interceptor service-map (ring-middlewares/resource root-path)))

(defn create-and-start
  "Creates the connector and starts it.  This may block the current thread until the connector is stopped.

  Returns the connector."
  [service-map create-connector-fn & {:as connector-options}]
  (p/start-connector (create-connector-fn service-map connector-options)))

(defn stop
  "A convienience for stopping the connector."
  [connector]
  (p/stop-connector connector))

