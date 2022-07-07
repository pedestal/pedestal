; Copyright 2013 Relevance, Inc.
; Copyright 2014-2019 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http
  "Namespace which ties all the pedestal components together in a
  sensible default way to make a full blown application."
  (:require [io.pedestal.http.route :as route]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.http.secure-headers :as sec-headers]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.interceptor :as pedestal.interceptor]
            [io.pedestal.interceptor.helpers :as interceptor]
            [io.pedestal.http.servlet :as servlet]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.http.cors :as cors]
            [ring.util.mime-type :as ring-mime]
            [ring.util.response :as ring-response]
            [clojure.string :as string]
            [cheshire.core :as json]
            [cognitect.transit :as transit]
            [io.pedestal.log :as log])
  (:import (java.io OutputStreamWriter
                    OutputStream)))

;; edn and json response formats

(defn- print-fn
  [prn-fn]
  (fn [output-stream]
    (with-open [writer (OutputStreamWriter. output-stream)]
      (binding [*out* writer]
        (prn-fn))
      (.flush writer))))

(defn- data-response
  [f content-type]
  (ring-response/content-type
   (ring-response/response (print-fn f))
   content-type))

(defn edn-response
  "Return a Ring response that will print the given `obj` to the HTTP output stream in EDN format."
  [obj]
  (data-response #(pr obj) "application/edn;charset=UTF-8"))

(defn json-print
  "Print object as JSON to *out*"
  [obj]
  (json/generate-stream obj *out*))

(defn json-response
  "Return a Ring response that will print the given `obj` to the HTTP output stream in JSON format."
  [obj]
  (data-response #(json-print obj) "application/json;charset=UTF-8"))

;; Interceptors
;; ------------
;; We avoid using the macro-versions in here, to avoid complications with AOT.
;; The error you'd see would be something like,
;;   "java.lang.IllegalArgumentException:
;;      No matching ctor found for class io.pedestal.interceptor.helpers$after$fn__6188"
;; Where the macro tries to call a function on 0-arity, but the actual
;; interceptor (already compiled) requires a 2-arity version.

(def log-request
  "Log the request's method and uri."
  (interceptor/on-request
    ::log-request
    (fn [request]
      (log/info :msg (format "%s %s"
                             (string/upper-case (name (:request-method request)))
                             (:uri request)))
      (log/meter ::request)
      request)))

(defn response?
  "A valid response is any map that includes an integer :status
  value."
  [resp]
  (and (map? resp)
       (integer? (:status resp))))

(def not-found
  "An interceptor that returns a 404 when routing failed to resolve a route."
  (interceptor/after
    ::not-found
    (fn [context]
      (if-not (response? (:response context))
        (do (log/meter ::not-found)
          (assoc context :response (ring-response/not-found "Not Found")))
        context))))

(def html-body
  "Set the Content-Type header to \"text/html\" if the body is a string and a
  type has not been set."
  (interceptor/on-response
    ::html-body
    (fn [response]
      (let [body (:body response)
            content-type (get-in response [:headers "Content-Type"])]
        (if (and (string? body) (not content-type))
          (ring-response/content-type response "text/html;charset=UTF-8")
          response)))))

(def json-body
  "Set the Content-Type header to \"application/json\" and convert the body to
  JSON if the body is a collection and a type has not been set."
  (interceptor/on-response
    ::json-body
    (fn [response]
      (let [body (:body response)
            content-type (get-in response [:headers "Content-Type"])]
        (if (and (coll? body) (not content-type))
          (-> response
              (ring-response/content-type "application/json;charset=UTF-8")
              (assoc :body (print-fn #(json-print body))))
          response)))))

(defn transit-body-interceptor
  "Returns an interceptor which sets the Content-Type header to the
  appropriate value depending on the transit format. Converts the body
  to the specified Transit format if the body is a collection and a
  type has not been set. Optionally accepts transit-opts which are
  handed to trasit/writer and may contain custom write handlers.

  Expects the following arguments:

  iname                - namespaced keyword for the interceptor name
  default-content-type - content-type string to set in the response
  transit-format       - either :json or :msgpack
  transit-options      - optional. map of options for transit/writer"
  ([iname default-content-type transit-format]
   (transit-body-interceptor iname default-content-type transit-format {}))

  ([iname default-content-type transit-format transit-opts]
   (interceptor/on-response
    iname
    (fn [response]
      (let [body (:body response)
            content-type (get-in response [:headers "Content-Type"])]
        (if (and (coll? body) (not content-type))
          (-> response
              (ring-response/content-type default-content-type)
              (assoc :body (fn [^OutputStream output-stream]
                             (transit/write
                              (transit/writer output-stream transit-format transit-opts) body)
                             (.flush output-stream))))
          response))))))

(def transit-json-body
  "Set the Content-Type header to \"application/transit+json\" and convert the body to
  transit+json if the body is a collection and a type has not been set."
  (transit-body-interceptor
   ::transit-json-body
   "application/transit+json;charset=UTF-8"
   :json))

(def transit-msgpack-body
  "Set the Content-Type header to \"application/transit+msgpack\" and convert the body to
  transit+msgpack if the body is a collection and a type has not been set."
  (transit-body-interceptor
   ::transit-msgpack-body
   "application/transit+msgpack;charset=UTF-8"
   :msgpack))

(def transit-body
  "Same as `transit-json-body` --
  Set the Content-Type header to \"application/transit+json\" and convert the body to
  transit+json if the body is a collection and a type has not been set."
  transit-json-body)

(defn default-interceptors
  "Builds interceptors given an options map with keyword keys prefixed by namespace e.g.
  :io.pedestal.http/routes or ::bootstrap/routes if the namespace is aliased to bootstrap.

  Note:
    No additional interceptors are added if :interceptors key is set.

  Options:

  * :routes: Something that satisfies the io.pedestal.http.route/ExpandableRoutes protocol
    a function that returns routes when called, or a seq of route maps that defines a service's routes.
    If passing in a seq of route maps, it's recommended to use io.pedestal.http.route/expand-routes.
  * :router: The router implementation to to use. Can be :linear-search, :map-tree
    :prefix-tree, or a custom Router constructor function. Defaults to :map-tree, which fallsback on :prefix-tree
  * :file-path: File path used as root by the middlewares/file interceptor. If nil, this interceptor
    is not added. Default is nil.
  * :resource-path: File path used as root by the middlewares/resource interceptor. If nil, this interceptor
    is not added. Default is nil.
  * :method-param-name: Query string parameter used to set the current HTTP verb. Default is _method.
  * :allowed-origins: Determines what origins are allowed for the cors/allow-origin interceptor. If
     nil, this interceptor is not added. Default is nil.
  * :not-found-interceptor: Interceptor to use when returning a not found response. Default is
     the not-found interceptor. `nil` to disable.
  * :request-logger: Interceptor to log requests entering the interceptor chain. Default is
     the log-request interceptor. `nil` to disable.
  * :mime-types: Mime-types map used by the middlewares/content-type interceptor. Default is {}.
  * :enable-session: A settings map to include the session middleware interceptor. If nil, this interceptor
     is not added.  Default is nil.
  * :enable-csrf: A settings map to include the csrf-protection interceptor. This implies
     sessions are enabled. If nil, this interceptor is not added. Default is nil.
  * :secure-headers: A settings map for various secure headers.
     Keys are: [:hsts-settings :frame-options-settings :content-type-settings :xss-protection-settings :download-options-settings :cross-domain-policies-settings :content-security-policy-settings]
     If nil, this interceptor is not added.  Default is the default secure-headers settings
  * :path-params-decoder: An Interceptor to decode path params. Default is URL Decoding via `io.pedestal.http.route/path-params-decoder.
     If nil, this interceptor is not added."
  [service-map]
  (let [{interceptors ::interceptors
         request-logger ::request-logger
         routes ::routes
         router ::router
         file-path ::file-path
         resource-path ::resource-path
         method-param-name ::method-param-name
         allowed-origins ::allowed-origins
         not-found-interceptor ::not-found-interceptor
         ext-mime-types ::mime-types
         enable-session ::enable-session
         enable-csrf ::enable-csrf
         secure-headers ::secure-headers
         path-params-decoder ::path-params-decoder
         :or {file-path nil
              request-logger log-request
              router :map-tree
              resource-path nil
              not-found-interceptor not-found
              method-param-name :_method
              ext-mime-types {}
              enable-session nil
              enable-csrf nil
              secure-headers {}
              path-params-decoder route/path-params-decoder}} service-map
        processed-routes (cond
                           (satisfies? route/ExpandableRoutes routes) (route/expand-routes routes)
                           (fn? routes) routes
                           (nil? routes) nil
                           (and (seq? routes) (every? map? routes)) routes
                           :else (throw (ex-info "Routes specified in the service map don't fulfill the contract.
                                                 They must be a seq of full-route maps or satisfy the ExpandableRoutes protocol"
                                                 {:routes routes})))]
    (if-not interceptors
      (assoc service-map ::interceptors
             (cond-> []
               (some? request-logger) (conj (pedestal.interceptor/interceptor request-logger))
               (some? allowed-origins) (conj (cors/allow-origin allowed-origins))
               (some? not-found-interceptor) (conj (pedestal.interceptor/interceptor not-found-interceptor))
               (or enable-session enable-csrf) (conj (middlewares/session (or enable-session {})))
               (some? enable-csrf) (into [(body-params/body-params (:body-params enable-csrf (body-params/default-parser-map)))
                                          (csrf/anti-forgery enable-csrf)])
               true (conj (middlewares/content-type {:mime-types ext-mime-types}))
               true (conj route/query-params)
               (some? path-params-decoder) (conj path-params-decoder)
               true (conj (route/method-param method-param-name))
               (some? secure-headers) (conj (sec-headers/secure-headers secure-headers))
               ;; TODO: If all platforms support async/NIO responses, we can bring this back
               ;(not (nil? resource-path)) (conj (middlewares/fast-resource resource-path))
               (some? resource-path) (conj (middlewares/resource resource-path))
               (some? file-path) (conj (middlewares/file file-path))
               true (conj (route/router processed-routes router))))
      service-map)))

(defn dev-interceptors
  [service-map]
  (update-in service-map [::interceptors]
             #(vec (->> %
                        (cons cors/dev-allow-origin)
                        (cons servlet-interceptor/exception-debug)))))

;; TODO: Make the next three functions a provider
(defn service-fn
  [{interceptors ::interceptors
    :as service-map}]
  (assoc service-map ::service-fn
         (servlet-interceptor/http-interceptor-service-fn interceptors)))

(defn servlet
  [{service-fn ::service-fn
    :as service-map}]
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
  [service-map]
  (let [provider (cond
                   (fn? (::chain-provider service-map)) (::chain-provider service-map)
                   (keyword? (::type service-map)) (comp servlet service-fn)
                   :else (throw (IllegalArgumentException. "There was no provider or server type specified.
                                                           Unable to create/connect interceptor chain foundation.
                                                           Try setting :type to :jetty in your service map.")))]
    (provider service-map)))

(defn create-provider
  "Creates the base Interceptor Chain provider, connecting a backend to the interceptor
  chain."
  [service-map]
  (-> service-map
      default-interceptors
      interceptor-chain-provider))

(defn- service-map->server-options
  [service-map]
  (let [server-keys [::host ::port ::join? ::container-options]]
    (into {} (map (fn [[k v]] [(keyword (name k)) v]) (select-keys service-map server-keys)))))

(defn- server-map->service-map
  [server-map]
  (into {} (map (fn [[k v]] [(keyword "io.pedestal.http" (name k)) v]) server-map)))

(defn server
  [service-map]
  (let [{type ::type
         :or {type :jetty}} service-map
        ;; Ensure that if a host arg was supplied, we default to a safe option, "localhost"
        service-map-with-host (if (::host service-map)
                                service-map
                                (assoc service-map ::host "localhost"))
        server-fn (if (fn? type)
                    type
                    (let [server-ns (symbol (str "io.pedestal.http." (name type)))]
                      (require server-ns)
                      (resolve (symbol (name server-ns) "server"))))
        server-map (server-fn service-map (service-map->server-options service-map-with-host))]
    (when (= type :jetty)
      ;; Load in container optimizations (NIO)
      (require 'io.pedestal.http.jetty.container))
    (when (= type :immutant)
      ;; Load in container optimizations (NIO)
      (require 'io.pedestal.http.immutant.container))
    (merge service-map-with-host (server-map->service-map server-map))))

(defn create-server
  "Given a service map, creates an returns an initialized service map which is
  ready to be started via `io.pedestal.http/start`. If init-fn, a zero
  arg function, is provided, it is invoked first.

  Notes:
  - The returned, initialized service map contains the `io.pedestal.http/start-fn`
    and `io.pedestal.http/stop-fn` keys whose values are zero arg functions which
    are used to start/stop the http service, respectively.
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
      create-provider ;; Creates/connects a backend to the interceptor chain
      server)))

(defn start
  "Given service-map, an initialized service map returned by `create-server`,
  invokes the zero-arg function assoc'd to the service map via `:io.pedestal.http/start-fn.`

  Returns `service-map` on success."
  [service-map]
  ((::start-fn service-map))
  service-map)

(defn stop
  "Given service-map, an initialized service map returned by `create-server`,
  invokes the zero-arg function assoc'd to the service map via `:io.pedestal.http/stop-fn.`

  Returns `service-map` on success."
  [service-map]
  ((::stop-fn service-map))
  service-map)

;; Container prod mode for use with the io.pedestal.servlet.ClojureVarServlet class.

(defn servlet-init
  [service config]
  (let [service (create-servlet service)]
    (.init ^javax.servlet.Servlet (::servlet service) config)
    service))

(defn servlet-destroy [service]
  (dissoc service ::servlet))

(defn servlet-service [service servlet-req servlet-resp]
  (.service ^javax.servlet.Servlet (::servlet service) servlet-req servlet-resp))
