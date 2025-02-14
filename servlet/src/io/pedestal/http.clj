; Copyright 2023-2025 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http
  "This namespace ties together the many other namespaces to make it possible
  to succinctly define a service and start and stop a server for that service.

  This namespace is generic as to the underlying container for the server;
  in some cases, a namespace will be loaded on the fly to convert a service to
  a server (based on Jetty or Tomcat, or others).

  In addition, there is support here for deploying a Pedestal application as part of a
  WAR file, via the ClojureVarServlet."
  (:require [io.pedestal.http.route :as route]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.http.secure-headers :as sec-headers]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.servlet :as servlet]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.http.cors :as cors]
            [io.pedestal.internal :as internal :refer [deprecated]]
            [io.pedestal.metrics :as metrics]
            [io.pedestal.http.tracing :as tracing]
            [io.pedestal.service.dev :as dev]
            [io.pedestal.service.interceptors :as interceptors]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.interceptor.chain.debug :as chain.debug]
            [io.pedestal.service.protocols :as sp]
            [io.pedestal.http.response :as response]
            [clojure.string :as string]
            [io.pedestal.log :as log])
  (:import (jakarta.servlet Servlet)
           (jakarta.servlet.http HttpServletResponse)))

;; This is the majority case; attempting to require it here helps with applications that AOT.
(try
  (require 'io.pedestal.http.jetty)
  (catch Exception _))

(extend-protocol sp/ResponseBufferSize

  HttpServletResponse

  (response-buffer-size [response]
    (.getBufferSize response)))

;; edn and json response formats

(defn edn-response
  "Return a Ring response that will print the given `obj` to the HTTP output stream in EDN format.

  DEPRECATED: Use io.pedestal.http.response/edn-response instead."
  {:deprecated "0.8.0"}
  [obj]
  (deprecated `edn-response
    (response/edn-response obj)))

(defn json-response
  "Return a Ring response that will print the given `obj` to the HTTP output stream in JSON format.

  DEPRECATED: Use io.pedestal.http.response/json-response instead."
  {:deprecated "0.8.0"}
  [obj]
  (response/json-response obj))

;; Interceptors
;; ------------

(def ^:private request-meter-fn (metrics/counter ::request nil))

(def ^{:deprecated "0.8.0"}
  log-request
  "Log the request's method and uri.

  DEPRECATED: Use io.pedestal.service.interceptors/log-request instead."
  (interceptor
    {:name  ::log-request
     :enter (fn [context]
              (let [{:keys [request]} context
                    {:keys [uri request-method]} request]
                (log/info :msg (format "%s %s"
                                       (-> request-method name string/upper-case)
                                       uri))
                (request-meter-fn)
                context))}))

(defn response?
  "A valid response is any map that includes an integer :status value.

  DEPRECATED: Use io.pedestal.http.response/response? instead."
  {:deprecated "0.8.0"}
  [resp]
  (deprecated `response?
    (response/response? resp)))

(def ^{:deprecated "0.8.0"} not-found
  "An interceptor that returns a 404 when routing failed to resolve a route, or no :response
  map was attached to the context.

  DEPRECATED: Use io.pedestal.service.interceptors/not-found instead."
  (assoc interceptors/not-found :name ::not-found))

(def ^{:deprecated "0.8.0"} html-body
  "Set the Content-Type header to \"text/html\" if the body is a string and a
  type has not been set.

  DEPRECATED: Use io.pedestal.service.interceptors/html-body instead."
  (assoc interceptors/html-body :name ::html-body))

(def ^{:deprecated "0.8.0"} json-body
  "Set the Content-Type header to \"application/json\" and convert the body to
  JSON if the body is a collection and a type has not been set.

  DEPRECATED: Use io.pedestal.service.interceptors/json-body instead."
  (assoc interceptors/json-body :name ::json-body))

(defn ^{:deprecated "0.8.0"} transit-body-interceptor
  "Returns an interceptor which sets the Content-Type header to the
  appropriate value depending on the transit format. Converts the body
  to the specified Transit format if the body is a collection and a
  type has not been set. Optionally accepts transit-opts which are
  handed to trasit/writer and may contain custom write handlers.

  Expects the following arguments:

  iname                - namespaced keyword for the interceptor name
  default-content-type - content-type string to set in the response
  transit-format       - either :json or :msgpack
  transit-options      - optional. map of options for transit/writer


  DEPRECATED: Use io.pedestal.service.interceptors/transit-body-interceptor instead."
  ([iname default-content-type transit-format]
   (deprecated `transit-body-interceptor
     (interceptors/transit-body-interceptor iname default-content-type transit-format {})))
  ([iname default-content-type transit-format transit-opts]
   (deprecated `transit-body-interceptor
     (interceptors/transit-body-interceptor iname default-content-type transit-format transit-opts))))

(def ^{:deprecated "0.8.0"} transit-json-body
  "Set the Content-Type header to \"application/transit+json\" and convert the body to
  transit+json if the body is a collection and a type has not been set.

  DEPRECATED: Use io.pedestal.service.interceptors/transit-json-body instead."
  (assoc interceptors/transit-json-body :name ::transit-json-body))

(def ^{:deprecated "0.8.0"} transit-msgpack-body
  "Set the Content-Type header to \"application/transit+msgpack\" and convert the body to
  transit+msgpack if the body is a collection and a type has not been set.

  DEPRECATED: Use io.pedestal.service.interceptors/transit-msgpack-body instead."
  (assoc interceptors/transit-msgpack-body :name ::transit-msgpack-body))

(def ^{:deprecated "0.8.0"} transit-body
  "Alias for [[transit-json-body]].

  DEPRECATED: Use io.pedestal.service.interceptors/transit-json-body instead."
  transit-json-body)

(defn default-interceptors
  "Builds a default vector of interceptors given a service map with keyword keys prefixed by namespace e.g.
  :io.pedestal.http/routes (or ::http/routes if the namespace is aliased to `http`); the interceptor are
  added to the ::interceptors key.

  This function is called from [[create-servlet]] and [[create-provider]].

  When the ::interceptors key is already present in the context, this function does nothing.
  This allows  application code to invoke default-interceptors, and then add or modify those interceptors
  before continuing on towards creating and starting a server.

  Options:

  * :routes: Something that satisfies the [[ExpandableRoutes]] protocol,
    a function that returns expanded routes when called, expanded routes
     (from calling [[expand-routes]], or a _seq of route maps that defines a service's routes_ (this last
     case is _deprecated_).
  * :router: The router constructor to use. Can be :linear-search, :map-tree
    :prefix-tree, :sawtooth, or a custom router constructor function. Defaults to :sawtooth.
  * :file-path: File path used as root by the middlewares/file interceptor (exposing a local directory
     as the root). If nil, this interceptor
    is not added. Default is nil.
  * :resource-path: Resource path (on the classpath) used as root by the [[resource]] interceptor; If nil, no interceptor
    is added. Default is nil. Alternately, include [[resource-routes]] in the :routes key.
  * :method-param-name: Query string parameter used to set the current HTTP verb. Default is `_method`.
  * :allowed-origins: Determines what origins are allowed for the [[allow-origin]] interceptor. If
     nil, this interceptor is not added. Default is nil.
  * :not-found-interceptor: Interceptor to use when returning a not found response. Default is
     the [[not-found]] interceptor. Set to nil to disable.
  * :request-logger: Interceptor to log requests entering the interceptor chain. Default is
     the [[log-request]] interceptor. Set to nil to disable request logging.
  * :mime-types: Mime-types map used by the [[content-type]] interceptor. Defaults to an empty map.
  * :enable-session: A settings map to include the [[session]] middleware interceptor. If nil, this interceptor
     is not added.  Default is nil.
  * :enable-csrf: A settings map to include the [[csrf-protection]] interceptor. This implies
     sessions are enabled. If nil, no interceptor is added. Default is nil.
  * :secure-headers: A settings map for various secure headers.
     Keys are: [:hsts-settings :frame-options-settings :content-type-settings :xss-protection-settings :download-options-settings :cross-domain-policies-settings :content-security-policy-settings]
     If nil, this interceptor is not added.  Default is the default secure-headers settings
  * :path-params-decoder: An interceptor to decode path params. Default [[path-params-decoder]].
     If nil, this interceptor is not added.
  * :tracing: An interceptor to handle telemetry request tracing; this is added as the first interceptor. Defaults
    to [[request-tracing-interceptor]] and can be set to nil to eliminate entirely (added in 0.7.0).


  Note that none of the default interceptors will parse the content of the request body (for POST or other requests);
  individual _routes_ that are of type POST should include the [[body-params]] interceptor to do so."
  [service-map]
  (let [{::keys [interceptors
                 request-logger
                 routes
                 router
                 file-path
                 resource-path
                 method-param-name
                 allowed-origins
                 not-found-interceptor
                 ext-mime-types
                 enable-session
                 enable-csrf
                 secure-headers
                 path-params-decoder
                 tracing]
         :or    {file-path             nil
                 request-logger        log-request
                 router                :sawtooth
                 resource-path         nil
                 not-found-interceptor not-found
                 method-param-name     :_method
                 ext-mime-types        {}
                 enable-session        nil
                 enable-csrf           nil
                 secure-headers        {}
                 path-params-decoder   route/path-params-decoder
                 tracing               (tracing/request-tracing-interceptor)}} service-map
        routing-table-or-fn (cond
                              (route/is-routing-table? routes) routes
                              (satisfies? route/ExpandableRoutes routes) (route/expand-routes routes)
                              (fn? routes) routes
                              (nil? routes) nil
                              ;; This checks for an expanded route; a seq of maps, each presumably a route.
                              (and (seq? routes) (every? map? routes))
                              (internal/deprecated
                                "Passing a seq of route maps as :io.pedestal.http/routes in service map"
                                (route/expand-routes {:children routes}))
                              :else (throw (ex-info (str "Routes specified in the service map don't fulfill the contract, "
                                                         "they must be expanded routes, a function that returns expanded routes, or a RoutingFragment")
                                                    {:routes routes})))]
    (if-not interceptors
      (assoc service-map ::interceptors
             (cond-> []
               (some? tracing) (conj tracing)
               (some? request-logger) (conj (interceptor request-logger))
               (some? allowed-origins) (conj (cors/allow-origin allowed-origins))
               (some? not-found-interceptor) (conj (interceptor not-found-interceptor))
               (or enable-session enable-csrf) (conj (middlewares/session (or enable-session {})))
               (some? enable-csrf) (conj (body-params/body-params (:body-params enable-csrf (body-params/default-parser-map)))
                                         (csrf/anti-forgery enable-csrf))
               true (conj (middlewares/content-type {:mime-types ext-mime-types}))
               true (conj route/query-params)
               true (conj (route/method-param method-param-name))
               (some? secure-headers) (conj (sec-headers/secure-headers secure-headers))
               (some? resource-path) (conj (middlewares/resource resource-path))
               (some? file-path) (conj (middlewares/file file-path))
               true (conj (route/router routing-table-or-fn router))
               (some? path-params-decoder) (conj path-params-decoder)))
      service-map)))

(defn dev-interceptors
  "Add [[dev-allow-origin]] and [[exception-debug]] interceptors to facilitate local development.

  This should normally be invoked after [[default-interceptors]]."
  [service-map]
  (update service-map ::interceptors
          #(into [cors/dev-allow-origin servlet-interceptor/exception-debug] %)))

(defn default-debug-observer-omit
  "Default for key paths to ignore when using [[debug-observer]].  This is primarily the
  request and response bodies, anything private to the io.pedestal.interceptor.chain namespaces,
  and a few routing-related keys (that produce non-useful logged output)."
  {:added      "0.7.0"
   :deprecated "0.8.0"}
  [key-path]
  (deprecated `default-debug-observer-omit
    (dev/default-debug-observer-omit key-path)))

(defn ^{:added "0.7.0"}
  enable-debug-interceptor-observer
  "Enables [[debug-observer]] for all request executions.  By default, uses
  [[default-debug-observer-omit]] to omit internal or overly verbose context map
  keys.

  The debug observer should not be enabled in production: it is somewhat expensive
  to identify changes to the context, and some data in the context that might be
  logged can be verbose, sensitive, or both.

  This modifies the ::initial-context key of the service map."
  ([service-map]
   (enable-debug-interceptor-observer service-map {:omit dev/default-debug-observer-omit}))
  ([service-map debug-observer-options]
   (update service-map ::initial-context
           chain/add-observer (chain.debug/debug-observer debug-observer-options))))

;; TODO: Make the next three functions a provider
(defn service-fn
  "Converts the interceptors for the service into a service function, which is a function
  that accepts a servlet, servlet request, and servlet response, and initiates the interceptor chain."
  [{::keys [interceptors initial-context service-fn-options]
    :as    service-map}]
  (assoc service-map ::service-fn
         (servlet-interceptor/http-interceptor-service-fn interceptors initial-context service-fn-options)))

(defn servlet
  "Converts the service-fn in the service map to a servlet instance."
  [{service-fn ::service-fn
    :as        service-map}]
  (assoc service-map ::servlet
         (servlet/servlet :service service-fn)))

(defn create-servlet
  "Creates a servlet given an options map with keyword keys prefixed by namespace e.g.
  :io.pedestal.http/interceptors or ::bootstrap/interceptors if the namespace is aliased to bootstrap.

  Options:

  * :io.pedestal.http/interceptors: A vector of interceptors that defines a service.

  Note: Additional options are passed to default-interceptors if :interceptors is not set."
  [service-map]
  (-> service-map
      default-interceptors
      service-fn
      servlet))

;;TODO: Make this a multimethod
(defn interceptor-chain-provider
  "Called from [[create-provider]], uses the ::chain-provider or ::type key of the service map
  to create the chain provider. "
  [service-map]
  (let [{::keys [chain-provider type]} service-map]
    (cond
      (fn? chain-provider) (chain-provider service-map)
      (or (keyword? type)
          (fn? type))
      (-> service-map service-fn servlet)
      :else (throw (IllegalArgumentException.
                     (str "There was no provider or server type specified. "
                          "Unable to create/connect interceptor chain foundation. "
                          "Try setting :type to :jetty in your service map."))))))

(defn create-provider
  "Applies [[default-interceptors]] (if not already applied) and creates the interceptor chain
  provider (the basis for starting an embedded servlet container)."
  [service-map]
  (-> service-map
      default-interceptors
      interceptor-chain-provider))

(defn- service-map->server-options
  ;; I think the idea is to make it easier for the jetty/server to access host, port, etc.
  ;; without having to use name-spaced keys.  Also ::host will be defaulted to "localhost".
  [service-map]
  (let [server-keys [::host ::port ::join? ::container-options ::websockets]]
    (into {} (map (fn [[k v]] [(keyword (name k)) v]) (select-keys service-map server-keys)))))

(defn- server-map->service-map
  "A service function (e.g., io.pedestal.jetty/service) returns a map with keys
  :start-fn and :stop-fn; this ensures that those key are name-spaced into the final
  server map)."
  [server-map]
  (into {} (map (fn [[k v]] [(keyword "io.pedestal.http" (name k)) v]) server-map)))

(defn server
  "Converts a service map to a server map.

   A service map is largely configuration, including some special callbacks; most of the keys are namespaced.
   Some keys are applicable to any underlying implementation (such as Jetty or Tomcat), and some are specific
   to the particular implementation.

   This function uses the ::type key to link the service map to a specific implementation; this should be
   a function that accepts a service map and returns a server map of an unstarted server.

   ::type can also be a keyword; this keyword is used to build a fully qualified symbol to resolve
   the function. For example, `:jetty` will be expanded into `io.pedestal.http.jetty/service` (but this
   approach is not favored as it can prevent AOT compilation from pre-compiling the indirectly referenced
   namespace).

   The function is passed the service map and options; these options are
   a subset of the keys from the service map (::host, ::port, ::join?, ::container-options, and ::websockets);
   they are extracted from the service map and passed as the options map, after stripping out
   the namespaces (::host becomes :host).

   Returns a server map, which merges the provided service map with additional keys from
   the map returned by the server-fn. The server map may be passed to [[start]] and [[stop]].

   A typical embedded app will call [[create-server]], rather than calling this function directly."
  [service-map]
  (let [{type ::type
         :or  {type :jetty}} service-map
        ;; Ensure that if a host arg was supplied, we default to a safe option, "localhost"
        service-map-with-host (if (::host service-map)
                                service-map
                                (assoc service-map ::host "localhost"))
        server-fn             (if (fn? type)
                                type
                                (let [server-ns (symbol (str "io.pedestal.http." (name type)))]
                                  (require server-ns)
                                  (resolve (symbol (name server-ns) "server"))))
        server-map            (server-fn service-map (service-map->server-options service-map-with-host))]
    (merge service-map-with-host (server-map->service-map server-map))))

(defn create-server
  "Given a service map, creates and returns an initialized server map which is
  ready to be started via [[start]]. If init-fn, a zero
  arg function, is provided, it is invoked first.

  Creating a server does not start the server; that occurs when the returned map
  is passed to [[start]].

  Notes:
  - If the service map option `:io.pedestal.http/chain-provider` is present,
    it is used to create the server, otherwise a servlet provider will be used.
    In this case, the type of servlet container created is determined by the
    `:io.pedestal.http/type` option.
  - For servlet containers, the resulting service-map will contain the
    `io.pedestal.http/service-fn` key which is useful for testing the service
    without starting it."
  ([service-map]
   (create-server service-map log/maybe-init-java-util-log))
  ([service-map init-fn]
   (init-fn)
   (-> service-map
       create-provider                                      ;; Creates/connects a backend to the interceptor chain
       server)))

(defn start
  "Given a server map, as returned by [[server]] (usually via [[create-server]]),
   starts the server. The server may later be stopped via [[stop]].

  Note that if the ::join? option is true, then this function will block until the
  started server stops.

  Returns the server map unchanged."
  [server-map]
  ((::start-fn server-map))
  server-map)

(defn stop
  "Given a server map (started by [[start]]), stops the server, if running.

  Returns the server map unchanged."
  [server-map]
  ((::stop-fn server-map))
  server-map)

;; Container prod mode for use with the io.pedestal.servlet.ClojureVarServlet class.

(defn servlet-init
  [service config]
  (let [service (create-servlet service)]
    (.init ^Servlet (::servlet service) config)
    service))

(defn servlet-destroy [service]
  (dissoc service ::servlet))

(defn servlet-service [service servlet-req servlet-resp]
  (.service ^Servlet (::servlet service) servlet-req servlet-resp))

(defn respond-with
  "Utility function to add a :response map to the interceptor context.

  DEPRECATED: Use io.pedestal.http.response/respond-with instead."
  {:added      "0.7.0"
   :deprecated "0.8.0"}
  ([context status]
   (deprecated `respond-with
     (response/respond-with context status)))
  ([context status body]
   (deprecated `respond-with
     (response/respond-with context status body)))
  ([context status headers body]
   (deprecated `respond-with
     (response/respond-with context status headers body))))
