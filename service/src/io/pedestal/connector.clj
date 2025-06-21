; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.connector
  "Replacement for io.pedestal.http that is not (directly) linked to the Jakarta Servlet API and is generally
   simpler to use."
  {:added "0.8.0"}
  (:require [io.pedestal.http.route :as route]
            [io.pedestal.http.tracing :as tracing]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.environment :refer [dev-mode?]]
            [io.pedestal.connector.dev :as dev]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]
            [io.pedestal.service.protocols :as p]
            io.pedestal.http.cors
            io.pedestal.http.body-params
            io.pedestal.http.secure-headers
            [io.pedestal.service.interceptors :as interceptors]))

(defn default-connector-map
  "Creates a default connector map for the given port and optional host.  host defaults to \"localhost\"
  which is appropriate for local testing (accessible only from the local host),
  but \"0.0.0.0\" (accessible from any TCP/IP connection) is a better option when deployed.

  The :router key defaults to :sawtooth; this can also be one of :map-tree, :prefix-tree, or
  :linear-search, or it can be a custom function to create a router function."
  ([port]
   (default-connector-map "localhost" port))
  ([host port]
   {:port            port
    :host            host
    :router          :sawtooth
    :interceptors    []
    :initial-context {}
    :join?           false}))

(defn with-interceptor
  "Appends to the :interceptors in the conector map, or returns the connector-map unchanged if interceptor is nil.

  interceptor must be an interceptor record, or convertable to an interceptor record."
  [connector-map interceptor]
  (cond-> connector-map
    interceptor
    (update  :interceptors conj (interceptor/interceptor interceptor))))

(defn with-interceptors
  "Appends a sequence of interceptors using [[with-interceptor]]."
  [connector-map interceptors]
  (reduce with-interceptor connector-map interceptors))

(defn optionally-with-dev-mode-interceptors
  "Conditionally adds [[dev-interceptors]] only when development mode is enabled."
  [connector-map]
  (cond-> connector-map
    dev-mode? (with-interceptors dev/dev-interceptors)))

(defmacro with-routes
  "A macro for adding a routing interceptor (and an interceptor to decode
  path parameters) to the connector map.
  This is generally the last step in building the interceptor chain.

  This is a wrapper around the [[routes-from]] macro, which helps with
  live code updates when developing at the REPL.

  The provided route-fragments must extend the [[ExpandableRoutes]] protocol; these will
  either be [[RoutingFragment]]s (from directly invoking a function such as
  [[table-routes]]) or a data structure (vector, map, or set) that can be implicitly
  converted to a RoutingFragment.

  At least one route fragment is required.

  Evalulates to the connector map with two added interceptors:

  - A routing interceptor
  - A [[path-params-decoder]]"
  [connector-map & route-fragments]
  `(let [connector-map# ~connector-map]
     (with-interceptors connector-map#
                        [(route/router (route/routes-from ~@route-fragments)
                                       (:router connector-map#))
                         route/path-params-decoder])))

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
  Not Found                  | Report lack of response as a status 404       | [[not-found]]
  Session support (optional) | Persist data between requests                 | [[session]]
  Body params                | Parse JSON, EDN, etc. body into appropriate  parameters (:edn-body, :json-body, etc.) | [[body-params]]
  Default content type       | Set response content type from request file extension, if not otherwise set | [[content-type]]
  Query parameters           | Decode request :query-string to :query-params | [[query-params]]
  Secure headers             | Ensures a number of security-related headers  | [[secure-headers]]

  Note that application code *should not* depend on the exact names of the interceptors, as those may be subject
  to change.

  Option            | Notes
  ------            |---
  :allowed-origins  | Passed to [[allow-origin]]
  :session-options  | If non-nil, passed to [[session]]
  :extra-mime-types | Passed to [[content-type]]
  :secure-headers   | Passed to [[secure-headers]]"
  [connector-map & {:as options}]
  (let [{:keys [allowed-origins
                session-options
                extra-mime-types
                secure-headers]} options]
    (with-interceptors connector-map
                       [(tracing/request-tracing-interceptor)
                        interceptors/log-request
                        (when allowed-origins
                          (io.pedestal.http.cors/allow-origin allowed-origins))
                        interceptors/not-found
                        (when session-options
                          (ring-middlewares/session session-options))
                        (ring-middlewares/content-type {:mime-types extra-mime-types})
                        route/query-params
                        (io.pedestal.http.body-params/body-params)
                        (io.pedestal.http.secure-headers/secure-headers secure-headers)])))

(defn start!
  "A convienience function for starting the connector.

  This may block the current thread until the connector is stopped.

  Returns the connector."
  [connector]
  (p/start-connector! connector))

(defn stop!
  "A convienience function for stopping the connector."
  [connector]
  (p/stop-connector! connector))

