; Copyright 2024-2025 Nubank NA
; Copyright 2014-2022 Cognitect, Inc.
; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route
  (:require [clojure.string :as str]
            [io.pedestal.http.route.types :as types]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as interceptor.chain]
            [io.pedestal.log :as log]
            [io.pedestal.http.route.definition :as definition]
            [io.pedestal.http.route.definition.terse :as terse]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.http.route.linear-search :as linear-search]
            [io.pedestal.http.route.map-tree :as map-tree]
            [io.pedestal.http.route.prefix-tree :as prefix-tree]
            [io.pedestal.http.route.sawtooth :as sawtooth]
            [io.pedestal.environment :refer [dev-mode?]]
            [io.pedestal.http.route.internal :as internal])
  (:import (clojure.lang APersistentMap APersistentSet APersistentVector)
           (io.pedestal.http.route.types RoutingFragment)
           (java.net URLEncoder URLDecoder)))

(defn is-routing-table?
  "Returns true if the value is a routing table (as returned from [[expand-routes]])."
  {:added "0.8.0"}
  [routing-table]
  (internal/is-routing-table? routing-table))

(def ^{:added   "0.7.0"
       :dynamic true} *print-routing-table*
  "If true, then the routing table is printed to the console at startup, and when it changes.

  Defaults to [[dev-mode?]]."
  dev-mode?)

;;; Parsing URL query strings (RFC 3986)

;; Java's URLEncoder/URLDecoder are only correct when applied on
;; *parts* of a query string. You must specify UTF-8 as the encoding.
;;
;; See http://www.lunatech-research.com/archives/2009/02/03/what-every-web-developer-must-know-about-url-encoding

(defn decode-query-part
  "Decodes one key or value of URL-encoded UTF-8 characters in a URL
  query string."
  [^String string]
  (URLDecoder/decode string "UTF-8"))

(defn encode-query-part
  "Encodes one key or value for a UTF-8 URL-encoded query string.
  Encodes space as +."
  [^String string]
  (URLEncoder/encode string "UTF-8"))

(defn- add!
  "Like 'assoc!' but creates a vector of values if the key already
  exists in the map. Ignores nil values."
  [m k v]
  (assoc! m k
          (if-let [p (get m k)]
            (if (vector? p) (conj p v) [p v])
            v)))

(defn parse-query-string
  "Parses URL query string (not including the leading '?') into1 a map.
  options are key-value pairs, valid options are:

     :key-fn    Function to call on parameter keys (after URL
                decoding), returns key for the map, default converts
                to a keyword.

     :value-fn  Function to call on the key (after passing through
                key-fn) and parameter value (after URL decoding),
                returns value for the map, default does nothing."
  [^String string & options]
  (let [{:keys [key-fn value-fn]
         :or   {key-fn   keyword
                value-fn (fn [_ v] v)}} options
        end (count string)]
    (loop [i   0
           m   (transient {})
           key nil
           b   (StringBuilder.)]
      (if (= end i)
        (persistent! (add! m key (value-fn key (decode-query-part (str b)))))
        (let [c (.charAt string i)]
          (cond
            (and (= \= c) (not key))                        ; unescaped = is allowed in values
            (recur (inc i)
                   m
                   (key-fn (decode-query-part (str b)))
                   (StringBuilder.))
            (= \& c)
            (recur (inc i)
                   (add! m key (value-fn key (decode-query-part (str b))))
                   nil
                   (StringBuilder.))
            :else
            (recur (inc i)
                   m
                   key
                   (.append b c))))))))

(defn- parse-query-string-params
  "Some platforms decode the query string automatically, providing a map of
  parameters instead.
  Process that map, returning an immutable map and supporting the same options
  as parse-query-string"
  [params & options]
  (let [{:keys [key-fn value-fn]
         :or   {key-fn   keyword
                value-fn (fn [_ v] v)}} options]
    (persistent!
      (reduce-kv
        (fn [acc k v]
          (let [newk (key-fn k)]
            (assoc! acc newk (value-fn newk v))))
        (transient {})
        params))))

(defn parse-query-params [request]
  (merge-with merge request
              (if-let [params (:query-string-params request)]
                (let [parsed-params (parse-query-string-params params)]
                  {:query-params parsed-params :params parsed-params})
                (when-let [string (:query-string request)]
                  (let [params (parse-query-string string)]
                    {:query-params params :params params})))))

(defn parse-param-map [m]
  (persistent! (reduce-kv (fn [acc k v] (assoc! acc k (decode-query-part v))) (transient {}) m)))

(defn parse-path-params [request]
  (if-let [m (:path-params request)]
    (let [res (assoc request :path-params (parse-param-map m))]
      res)
    request))

;; Code copied from https://github.com/clojure/core.incubator

(defn- dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

;;; Combined matcher & request handler

(defn- replace-method
  "Replace the HTTP method of a request with the value provided at
  param-path (if provided). Removes the value found at param-path."
  [param-path request]
  (if-let [method (get-in request param-path)]
    (-> request
        (assoc :request-method (keyword method))
        (dissoc-in param-path))
    request))

;;; Linker

(defn- merge-param-options
  "Merges the :params map into :path-params and :query-params. The
  :path-params keys are taken from the route, any other keys in
  :params are added to :query-params. Returns updated opts."
  [opts route]
  (let [{:keys [params request]} opts]
    (log/debug :msg "MERGE-PARAM-OPTIONS"
               :opts opts
               :params params
               :request request)
    (-> opts
        (dissoc :params)
        (update :path-params #(merge (:path-params request) params %))
        (update :query-params
                #(merge (apply dissoc params (:path-params route)) %)))))

(defn- merge-method-param
  "If the route's method is other than GET or POST and opts contains
  non-nil :method-param, adds the method name on to the :query-params
  map. Returns updated opts map. If opts does not contain
  :method-param, defaults to :_method."
  [opts route]
  (let [{:keys [method]} route
        {:keys [method-param]} opts]
    (if (and (not= :get method)
             (not= :post method)
             method-param)
      (assoc-in opts [:query-params method-param] (name method))
      opts)))

(defn- combine-opts [opts default-opts route]
  (-> (merge default-opts opts)
      (merge-param-options route)
      (merge-method-param route)))

(defn- context-path
  [{:keys [context request]}]
  (log/debug :in :context-path
             :context context
             :context-type (type context)
             :resolved-context (when (symbol? context) (resolve context))
             :request request)
  (when-let [context-str (cond
                           (string? context) context
                           (fn? context) (context)
                           (symbol? context) ((resolve context))
                           :else (:context-path request))]
    (str/split context-str #"/")))

(def ^{:private true} standard-scheme->port {:http  80
                                             :https 443})

(defn- non-standard-port?
  [scheme port]
  (not= port (standard-scheme->port scheme)))

(defn- link-str
  "Returns a string for a route, providing the minimum URL necessary
  given the route and opts. opts is a map as described in the
  docstring for 'url-for'."
  [route opts]
  (let [{:keys           [path-params
                          strict-path-params?
                          query-params
                          request
                          fragment
                          absolute?]
         override-host   :host
         override-port   :port
         override-scheme :scheme} opts
        {:keys [scheme host port path-parts path]} route
        context-path-parts (context-path opts)
        path-parts         (do (log/debug :in :link-str
                                          :path-parts path-parts
                                          :context-path-parts context-path-parts)
                               (cond
                                 (and context-path-parts (= "" (first path-parts))) (concat context-path-parts (rest path-parts))
                                 context-path-parts (concat context-path-parts path-parts)
                                 :else path-parts))
        _                  (when (and (true? strict-path-params?)
                                      (or
                                        (not= (set (keys path-params)) ;; Do the params passed in...
                                              (set (seq (:path-params route))) ;; match the params from the route?  `seq` is used to handle cases where no `path-params` are required
                                              )
                                        ;; nils are not allowed.
                                        (reduce-kv #(if (nil? %3) (reduced true) false) nil path-params)))
                             (throw (ex-info "Attempted to create a URL with `url-for`, but missing required :path-params - :strict-path-params was set to true.
                            Either include all path-params (`nil` is not allowed), or if your URL actually contains ':' in the path, set :strict-path-params to false in the options"
                                             {:path-parts  path-parts
                                              :path-params path-params
                                              :options     opts
                                              :route       route})))
        path-chunk         (str/join \/ (map #(get path-params % %) path-parts))
        path               (if (and (= \/ (last path))
                                    (not= \/ (last path-chunk)))
                             (str path-chunk "/")
                             path-chunk)
        request-scheme     (:scheme request)
        request-host       (:server-name request)
        request-port       (:server-port request)
        scheme             (or override-scheme scheme request-scheme)
        host               (or override-host host request-host)
        port               (or override-port port request-port)
        scheme-mismatch    (not= scheme request-scheme)
        host-mismatch      (not= host request-host)
        port-mismatch      (not= port request-port)]
    (str
      (when (or absolute? scheme-mismatch host-mismatch port-mismatch)
        (str (when (or absolute? scheme-mismatch) (str (name scheme) \:))
             "//"
             host
             (when (non-standard-port? scheme port) (str \: port))))
      (when-not (.startsWith path "/") "/")
      path
      (when-not (str/blank? fragment) (str "#" fragment))
      (when (seq query-params)
        (str \?
             (str/join \& (map (fn [[k v]]
                                 (str (encode-query-part (name k))
                                      \=
                                      (encode-query-part (str v))))
                               query-params)))))))

(defn- linker-map
  "Returns a map like {app-name {route-name route}}.
  Routes without an application name will have nil as the app-name key."
  [routes]
  (reduce (fn [m route]
            (let [{:keys [app-name route-name]} route]
              (assoc-in m [app-name route-name] route)))
          {} routes))

(defn- find-route
  "Finds and returns a route in the map returned by linker-map, or
  throws an exception if not found."
  [m app-name route-name]
  (or (get-in m [app-name route-name])
      (get-in m [nil route-name])
      (throw (ex-info "Route not found"
                      {:app-name   app-name
                       :route-name route-name}))))

(defn url-for-routes
  "Returns a function that generates URL routes (as strings) from the
  routing table. The returned function has the signature:

  ```
      [route-name & options]
  ```

  Where `options` are key-value pairs:

  Key           | Value           | Description
  ---           |---              |---
  :app-name     | String          | Application name specified for this route
  :request      | Map             | The original request; it will be merged into the generated link
  :params       | Map             | A map of all parameters; any params not used as path parameters will be added to the query string
  :path-params  | Map             | A map of path parameters only
  :strict-path-params? | Boolean  | When true will throw an exception if all path-params aren't fulfilled for the URL
  :query-params | Map             | A map of query-string parameters only
  :method-param | Keyword         | Names the query-string parameter in which to place the HTTP method name (used when not :get or :post)
  :context      | varied          | String, function that returns a string, or symbol that resolves to a function; specifies root context for the URL
  :fragment     | String          | The fragment part of the URL
  :absolute?    | Boolean         | True to force an absolute URL
  :scheme       | :http, :https   | Used to override the scheme portion of the URL
  :host         | String          | Used to override the host portion of the URL
  :port         | Integer         | Used to override the port in the URL

  In addition, you may supply default-options to the 'url-for-routes'
  function, which are merged with the options supplied to the returned
  function."
  [routing-table & default-options]
  {:pre []}
  (let [routes (internal/extract-routes routing-table)
        {:as default-opts} default-options
        m      (linker-map routes)]
    (fn [route-name & options]
      (let [{:keys [app-name] :as options-map} options
            default-app-name (:app-name default-opts)
            route            (find-route m (or app-name default-app-name) route-name)
            opts             (combine-opts options-map default-opts route)]
        (link-str route opts)))))

(def ^:private ^:dynamic *url-for*
  "Dynamic var which holds the 'contextual' linker. The contextual
 linker is created from the router at routing time. The router will
 create the linker based on.

   - The routing table it is routing against.
   - The incoming request it has just routed."
  nil)

(defn url-for
  "Used by an invoked interceptor (including a handler function)
  to generate URLs based on a known route name (from the routing specification),
  and additional data.

  This uses a hidden dynamic variable, so it can only be invoked
  from request processing threads, and only *after* the routing interceptor has routed
  the request.

  The available options are as described in [[url-for-routes]]."
  [route-name & options]
  (if *url-for*
    ;; The linker (stored in *url-for*) is rarely used outside of tests, so it is a delay object
    ;; that must be de-referenced so it can initialize on first invocation.
    (apply @*url-for* route-name options)
    (throw (ex-info "*url-for* not bound" {}))))

(defprotocol ExpandableRoutes
  "A protocol extended onto types that can be used to convert instances into a
  [[RoutingFragment]].  The fragments are combined into a routing table
  by [[expand-routes]].

  Built-in implementations map vectors to [[terse-routes]],
  sets to [[table-routes]], and maps to [[map-routes->vec-routes]]."
  (-expand-routes [expandable-route-spec]
    "Generate and return a [[RoutingFragment]] from the data."))

(extend-protocol ExpandableRoutes

  RoutingFragment
  (-expand-routes [fragment]
    fragment)

  APersistentVector
  (-expand-routes [route-spec]
    (terse/terse-routes route-spec))

  APersistentMap
  (-expand-routes [route-spec]
    (-expand-routes [[(terse/map-routes->vec-routes route-spec)]]))

  APersistentSet
  (-expand-routes [route-spec]
    (table/table-routes route-spec)))

(defn- check-satifies-expandable-routes
  [route-spec]
  (when-not (satisfies? ExpandableRoutes route-spec)
    (throw (ex-info "Value does not satisfy io.pedestal.http.route/ExpandableRoutes"
                    {:value route-spec})))
  route-spec)

(defn expand-routes
  "Converts any number of route fragments into a fully expanded routing table.

  Route fragments are created by route definitions (such as [[table-routes]]), or when
  data structures are implicitly converted (via the [[ExpandableRoutes]] protocol).

  Returns a wrapper object with a :routes key; the routes themselves are verified
  to have unique route names (or an exception is thrown)."
  [& route-specs]
  {:pre [(seq route-specs)]}
  ;; This is the case when the ::http/route is the result of
  ;; calling expand-routes; this prevents expand-routes from being invoked
  ;; a second time.
  (when-not (seq route-specs)
    (throw (IllegalArgumentException. "Must provide at least one routing specification")))
  (->> route-specs
       (map check-satifies-expandable-routes)
       (map -expand-routes)
       (mapcat types/fragment-routes)
       definition/verify-unique-route-names
       internal/->RoutingTable))


(defn- route-context
  [context router-fn routing-table]
  (if-let [[route path-params] (router-fn (:request context))]
    ;;  This is where path-params are added to the request.
    (let [request' (assoc (:request context) :path-params path-params)
          ;; Rarely used, potentially expensive to create, delay creation until needed.
          linker   (delay (url-for-routes routing-table :request request'))]
      (-> context
          (assoc :route route
                 :request (assoc request' :url-for linker)
                 :url-for linker)
          (assoc-in [:bindings #'*url-for*] linker)
          (interceptor.chain/enqueue (:interceptors route))))
    ;; Key present but nil indicates that routing failed (the request could not be
    ;; mapped to a route).
    (assoc context :route nil)))

(defn- construct-router-interceptor-from-table
  [routing-table router-ctor]
  {:pre [is-routing-table?]}
  (let [routes    (:routes routing-table)
        router-fn (router-ctor routes)]
    (interceptor/interceptor
      {:name  ::router
       :enter #(route-context % router-fn routing-table)})))

(defn- construct-router-interceptor-from-fn
  [f router-ctor]
  (interceptor/interceptor
    {:name  ::router
     :enter (fn [context]
              ;; The table and the router are rebuilt on each execution; good for development,
              ;; very, very, very bad for production.
              (let [routing-table (f)
                    routes        (:routes routing-table)
                    router-fn     (router-ctor routes)]
                (route-context context router-fn routing-table)))}))

(def router-implementations
  "Maps from the common router implementations (:map-tree, :prefix-tree, :sawtooth,
  or :linear-search) to a router constructor function (which accepts a routing table, and returns a Router instance)."
  {:map-tree      map-tree/router
   :prefix-tree   prefix-tree/router
   :linear-search linear-search/router
   :sawtooth      sawtooth/router})

(defn router
  "Given the expanded routing table and, optionally, what kind of router to construct,
  creates and returns a router interceptor.

  router-type may be a keyword identifying a known implementation (see [[router-implementations]]),
  or a function that accepts a seq of route maps, and returns a router function.

  A router function will be passed the request map, and return nil, or a matching route.

  The default router type is :sawtooth."
  ([routing-table]
   (router routing-table :sawtooth))
  ([routing-table router-type]
   (assert (or (contains? router-implementations router-type)
               (fn? router-type))
           (format "No router implementation exists for key %s. Please use one of %s."
                   router-type
                   (keys router-implementations)))
   (let [router-ctor    (if (fn? router-type)
                          router-type
                          (router-type router-implementations))
         routing-table' (cond-> routing-table
                          *print-routing-table* internal/wrap-routing-table)]
     (if (fn? routing-table')
       (construct-router-interceptor-from-fn routing-table' router-ctor)
       (construct-router-interceptor-from-table routing-table' router-ctor)))))

(defn- attach-bad-request-response
  [context exception]
  (assoc context :response
         {:status  400
          :headers {}
          :body    (str "Bad Request - " (ex-message exception))}))

(def query-params
  "An interceptor which parses query-string parameters from an
  HTTP request into a map. Keys in the map are query-string parameter
  names, as keywords, and values are strings. The map is assoc'd into
  the request with key :query-params."
  (interceptor/interceptor
    {:name  ::query-params
     :enter (fn [ctx]
              (try
                (update ctx :request parse-query-params)
                (catch IllegalArgumentException e
                  (attach-bad-request-response ctx e))))}))

(def path-params-decoder
  "An Interceptor which URL-decodes path parameters.
  The path parameters are assoc'd into the :request map with key :path-parameters.

  This will only operate once per interceptor chain execution, even if
  it appears multiple times; this prevents failures in existing applications
  that upgrade to Pedestal 0.6.0, as prior releases incorrectly failed to
  decode path parameters. Existing applications that upgrade may have
  this interceptor in some routes which will do nothing (since the path parameters
  will already have been decoded)."
  (interceptor/interceptor
    {:name  ::path-params-decoder
     :enter (fn [ctx]
              ;; This isn't truly idempotent, as it does not account for
              ;; some intermediate interceptor modifying the path parameters,
              ;; but this addresses the needs in issue #776.
              (if (::path-params-decoded? ctx)
                ctx
                (try
                  (-> ctx
                      (update :request parse-path-params)
                      (assoc ::path-params-decoded? true))
                  (catch IllegalArgumentException e
                    (attach-bad-request-response ctx e)))))}))

(defn method-param
  "Returns an interceptor that smuggles HTTP verbs through a value in
  the request. This interceptor must come *after* the interceptor that populates that
  value (e.g. query-params or body-params).

  query-param-or-param-path may be one of two things:

  - The parameter inside :query-params where the verb will
    reside.
  - A complete path to a value elsewhere in the request, such as
    [:query-params :_method] or [:body-params \"_method\"]

  The path [:query-params :_method] is used by default."
  ([]
   (method-param [:query-params :_method]))
  ([query-param-or-param-path]
   (let [param-path (if (vector? query-param-or-param-path)
                      query-param-or-param-path
                      [:query-params query-param-or-param-path])]
     (interceptor/interceptor
       {:name  ::method-param
        :enter (fn [ctx]
                 (update ctx :request #(replace-method param-path %)))}))))

(defn form-action-for-routes
  "Like 'url-for-routes' but the returned function returns a map with the keys
  :action, the URL string; and :method, the HTTP verb as a lower-case
  string. Also, the :method-param is :_method by default, so HTTP
  verbs other than GET and POST will be converted to POST with the
  actual verb in the query string.

  The routing-table is obtained from [[expand-routes]].
  "
  [routing-table & default-options]
  (let [routes (internal/extract-routes routing-table)
        {:as default-opts} default-options
        m      (linker-map routes)]
    (fn [route-name & options]
      (let [{:keys [app-name] :as options-map} options
            {:keys [method] :as route} (find-route m app-name route-name)
            opts (combine-opts options-map
                               (merge {:method-param :_method} default-opts) route)]
        {:action (link-str route opts)
         :method (name (if (and (:method-param opts)
                                (not (= :get method)))
                         :post
                         method))}))))

;;; Help for debugging
(defn print-routes
  "Prints a routing-table (from [[expand-routes]]) in an easier to read format."
  [routing-table]
  (internal/print-routing-table routing-table))

(defn try-routing-for
  "Used for testing; constructs a router from the routing-table and router-type and performs routing
  on the provided path and verb (e.g., :get or :post).

  Uses :sawtooth as the default router, if unspecified; this should match the configuration
  in the application's service map.

  Returns the matched route (a map from the routing table), or nil if routing was unsuccessful.

  The matched route has an extra key, :path-params, a map of keyword to string.

  The routing-table is obtained from [[expand-routes]]."
  ([routing-table path verb]
   (try-routing-for routing-table :sawtooth path verb))
  ([routing-table router-type path verb]
   (let [routing-interceptor (router routing-table router-type) ; create a disposable interceptor
         context             {:request {:path-info      path
                                        :request-method verb}}
         enter-fn            (:enter routing-interceptor)
         {:keys [request route]} (enter-fn context)]
     (when route
       (assoc route :path-params (:path-params request))))))

(defmacro routes-from
  "Wraps around one or more expressions that each provide a [[RoutingFragment]].

 In production mode (the default) evaluates to a call to [[expand-routes]].

 In development mode (see [[dev-mode?]]), evaluates to a function that, when invoked, returns the expressions
 passed to [[expand-routes]]; this
 is to support a REPL workflow. This works in combination with the extension of [[RouterSpecification]]
 onto Fn, which requires that the returned routing specification be expanded.

 Further, when the expression is a non-local symbol, it is assumed to identify a Var holding the unexpanded routing specification;
 to avoid capturing the Var's value, the expansion resolved and de-references the Var before passing it to expand-routes.

 This expansion of non-local symbols also applies to lists (that is, function calls), where the function being called
 and the parameters are (recursively) so expanded."
  {:added "0.7.0"}
  [& route-exprs]
  (if-not dev-mode?
    `(expand-routes ~@route-exprs)
    (internal/create-routes-from-fn route-exprs &env `expand-routes)))


