; Copyright 2024 Nubank NA
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
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as interceptor.chain]
            [io.pedestal.log :as log]
            [io.pedestal.http.route.definition :as definition]
            [io.pedestal.http.route.definition.terse :as terse]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.http.route.router :as router]
            [io.pedestal.http.route.linear-search :as linear-search]
            [io.pedestal.http.route.map-tree :as map-tree]
            [io.pedestal.http.route.prefix-tree :as prefix-tree]
            [io.pedestal.environment :refer [dev-mode?]]
            [io.pedestal.http.route.internal :as internal])
  (:import (clojure.lang APersistentMap APersistentSet APersistentVector Fn Sequential)
           (java.net URLEncoder URLDecoder)))


(comment
  ;; Structure of a route. 'tree' returns a list of these.
  {:route-name :new-user
   :app-name   :example-app        ; optional
   :path       "/user/:id/*blah"   ; like Ruby on Rails
                                   ; (catch-all route is "/*path")
   :method     :post               ; or :any, :get, :put, ...
   :scheme     :https              ; optional
   :host       "example.com"       ; optional
   :port       "8080"              ; optional
   :interceptors [...]             ; vector of interceptors to
                                   ; be enqueued on the context

   ;; Generated for path-matching:
   :path-re #"/\Quser\E/([^/]+)/(.+)"
   :path-parts ["user" :id :blah]
   :path-params [:id :blah]
   :path-constraints {:id "([^/]+)"
                      :blah "(.+)"}
   :query-constraints {:name #".+"
                       :search #"[0-9]+"}

   ;; Generated for routing:
   :matcher (fn [request] ...)    ; returns map from path-params to string
                                  ; values on match, nil on non-match
   })

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
  "Parses URL query string (not including the leading '?') into a map.
  options are key-value pairs, valid options are:

     :key-fn    Function to call on parameter keys (after URL
                decoding), returns key for the map, default converts
                to a keyword.

     :value-fn  Function to call on the key (after passing through
                key-fn) and parameter value (after URL decoding),
                returns value for the map, default does nothing."
  [^String string & options]
  (let [{:keys [key-fn value-fn]
         :or {key-fn keyword
              value-fn (fn [_ v] v)}} options]
    (let [end (count string)]
      (loop [i 0
             m (transient {})
             key nil
             b (StringBuilder.)]
        (if (= end i)
          (persistent! (add! m key (value-fn key (decode-query-part (str b)))))
          (let [c (.charAt string i)]
            (cond
             (and (= \= c) (not key)) ; unescaped = is allowed in values
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
                    (.append b c)))))))))

(defn- parse-query-string-params
  "Some platforms decode the query string automatically, providing a map of
  parameters instead.
  Process that map, returning an immutable map and supporting the same options
  as parse-query-string"
  [params & options]
  (let [{:keys [key-fn value-fn]
         :or {key-fn keyword
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
  [m [k & ks :as keys]]
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
  [{:keys [context request] :as opts}]
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
  (let [{:keys [path-params
                strict-path-params?
                query-params
                request
                fragment
                override
                absolute?]
         override-host   :host
         override-port   :port
         override-scheme :scheme} opts
        {:keys [scheme host port path-parts path]} route
        context-path-parts (context-path opts)
        path-parts (do (log/debug :in :link-str
                                  :path-parts path-parts
                                  :context-path-parts context-path-parts)
                       (cond
                         (and context-path-parts (= "" (first path-parts))) (concat context-path-parts (rest path-parts))
                         context-path-parts (concat context-path-parts path-parts)
                         :else path-parts))
        _ (when (and (true? strict-path-params?)
                     (or
                      (not= (set (keys path-params)) ;; Do the params passed in...
                            (set (seq (:path-params route))) ;; match the params from the route?  `seq` is used to handle cases where no `path-params` are required
                            )
                      ;; nils are not allowed.
                      (reduce-kv #(if (nil? %3) (reduced true)  false) nil path-params)))
            (throw (ex-info "Attempted to create a URL with `url-for`, but missing required :path-params - :strict-path-params was set to true.
                            Either include all path-params (`nil` is not allowed), or if your URL actually contains ':' in the path, set :strict-path-params to false in the options"
                            {:path-parts path-parts
                             :path-params path-params
                             :options opts
                             :route route})))
        path-chunk (str/join \/ (map #(get path-params % %) path-parts))
        path (if (and (= \/ (last path))
                      (not= \/ (last path-chunk)))
               (str path-chunk "/")
               path-chunk)
        request-scheme (:scheme request)
        request-host (:server-name request)
        request-port (:server-port request)
        scheme (or override-scheme scheme request-scheme)
        host (or override-host host request-host)
        port (or override-port port request-port)
        scheme-mismatch (not= scheme request-scheme)
        host-mismatch   (not= host   request-host)
        port-mismatch   (not= port   request-port)]
    (str
     (when (or absolute? scheme-mismatch host-mismatch port-mismatch)
       (str (when (or absolute? scheme-mismatch) (str (name scheme) \:))
            "//"
            host
            (when (non-standard-port? scheme port) (str \: port))))
     (str (when-not (.startsWith path "/") "/") path)
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
                      {:app-name app-name
                       :route-name route-name}))))

(defn url-for-routes
  "Returns a function that generates URL routes (as strings) from the
  routes table. The returned function has the signature:

     [route-name & options]

  Where 'options' are key-value pairs from:

     :app-name      Application name specified for this route

     :request       The original request; it will be merged into the
                    generated link.

     :params        A map of all parameters; any params not used as
                    path parameters will be added to the query string

     :path-params   A map of path parameters only

     :strict-path-params? A boolean, when true will throw an exception
                          if all path-params aren't fulfilled for the url

     :query-params  A map of query-string parameters only

     :method-param  Keyword naming the query-string parameter in which
                    to place the HTTP method name, if it is neither
                    GET nor POST. If nil, the HTTP method name will
                    not be included in the query string. Default is nil.

     :context       A string, function that returns a string, or symbol
                    that resolves to a function that returns a string
                    that specifies a root context for the URL. Default
                    is nil.

     :fragment      A string for the fragment part of the url.

     :absolute?     Boolean, whether to force an absolute URL

     :scheme        Keyword (:http | :https) used to override the scheme
                    portion of the url.

     :host          A string used to override the host portion of the URL.

     :port          An integer used to override the port in the URL.

  In addition, you may supply default-options to the 'url-for-routes'
  function, which are merged with the options supplied to the returned
  function."
  [routes & default-options]
  (let [{:as default-opts} default-options
        m (linker-map routes)]
    (fn [route-name & options]
      (let [{:keys [app-name] :as options-map} options
            default-app-name (:app-name default-opts)
            route (find-route m (or app-name default-app-name) route-name)
            opts (combine-opts options-map default-opts route)]
        (link-str route opts)))))

(def ^:private ^:dynamic *url-for*
  "Dynamic var which holds the 'contextual' linker. The contextual
 linker is created by the router at routing time. The router will
 create the linker based on.

   - The routing table it is routing against.
   - The incoming request it has just routed."
  nil)

(defn url-for
  "Invokes currently bound contextual linker to generate url based on

    - The routing table in use.
    - The incoming request being routed.

  where `options` are as described in [[url-for-routes]].

  This function may only be successfully called after routing has occurred.
  It's purpose is to allow an interceptor in the identified route to generate
  URL's for other routes (by name).
  "
  [route-name & options]
  (if *url-for*
    (apply (if (delay? *url-for*) (deref *url-for*) *url-for*)
           route-name options)
    (throw (ex-info "*url-for* not bound" {}))))

(defprotocol ExpandableRoutes
  "A protocol extended onto types that can be used to convert instances into a seq of verbose route maps,
  the routing table.

  Built-in implementations map vectors to [[terse-routes]],
  sets to [[table-routes]], and maps to [[map-routes->vec-routes]]."
  (-expand-routes [expandable-route-spec]
                  "Generate and return the routing table from a given expandable
                  form of routing data."))

(extend-protocol ExpandableRoutes
  APersistentVector
  (-expand-routes [route-spec]
    (terse/terse-routes route-spec))

  APersistentMap
  (-expand-routes [route-spec]
    (-expand-routes [[(terse/map-routes->vec-routes route-spec)]]))

  APersistentSet
  (-expand-routes [route-spec]
    (table/table-routes route-spec)))

(defn expand-routes
  "Given a value (the route specification), produce and return a routing table,
  a seq of verbose routing maps.

  A route specification is any type that satisfies [[ExpandableRoutes]];
  this includes Clojure vectors, maps, and sets (for terse, table, and verbose routes).

  Ensures the integrity of expanded routes (even if they've already been checked):

  - Constraints are correctly ordered (most specific to least specific)
  - Route names are unique"
  [route-spec]
  {:pre [(if-not (satisfies? ExpandableRoutes route-spec)
           (throw (ex-info "You're trying to use something as a route specification that isn't supported by the protocol; Perhaps you need to extend it?"
                           {:routes route-spec
                            :type (type route-spec)}))
           true)]
   :post [(seq? %)
          (every? (every-pred map? :path :route-name :method) %)]}
  (definition/ensure-routes-integrity (-expand-routes route-spec)))

(defprotocol RouterSpecification
  (router-spec [routing-table router-ctor]
    "Returns an interceptor which attempts to match each route against
    a :request in context. For the first route that matches, it will:

    - enqueue the matched route's interceptors
    - associate the route into the context at :route
    - associate a map of :path-params into the :request

  If no route matches, returns context with :route nil."))

(defn- route-context [context router routes]
  (if-let [route (router/find-route router (:request context))]
    ;;  This is where path-params are added to the request. vvvv
    (let [request-with-path-params (assoc (:request context) :path-params (:path-params route))
          linker (delay (url-for-routes routes :request request-with-path-params))]
      (-> context
          (assoc :route route
                 :request (assoc request-with-path-params :url-for linker)
                 :url-for linker)
          (assoc-in [:bindings #'*url-for*] linker)
          (interceptor.chain/enqueue (:interceptors route))))
    (assoc context :route nil)))

(extend-protocol RouterSpecification
  ;; Normally, we start with a verbose routing table and create a router from that
  ;; so RouterSpecification is extended on Sequential
  Sequential
  (router-spec [routing-table router-ctor]
    (let [router (router-ctor routing-table)]
      (interceptor/interceptor
        {:name ::router
         :enter #(route-context % router routing-table)})))

  ;; The alternative is to pass in a no-arguments function that returns the expanded routes.
  ;; That is only used in development, as it can allow for significant changes to routing and handling
  ;; without restarting the running application, but it is slow.
  Fn
  (router-spec [f router-ctor]
    (interceptor/interceptor
      {:name ::router
       :enter (fn [context]
                (let [routing-table (f)
                      router (router-ctor routing-table)]
                  (route-context context router routing-table)))})))

(def router-implementations
  "Maps from the common router implementations (:map-tree, :prefix-tree, or :linear-search) to a router
  constructor function (which accepts expanded routes, and returns a Router instance)."
  {:map-tree map-tree/router
   :prefix-tree prefix-tree/router
   :linear-search linear-search/router})

(defn router
  "Given the expanded routing table and, optionally, what kind of router to construct,
  creates and returns a router interceptor.

  router-type may be a keyword identifying a known implementation (see [[router-implementations]]), or function
  that accepts a routing table, and returns a [[Router]].

  The default router type is :map-tree, which is the fastest built-in router;
  however, if the expanded routes contain path parameters or wildcards,
  the result is equivalent to the slower :prefix-tree implementation."
  ([routing-table]
   (router routing-table :map-tree))
  ([routing-table router-type]
   (assert (or (contains? router-implementations router-type)
               (fn? router-type))
           (format "No router implementation exists for key %s. Please use one of %s."
                   router-type
                   (keys router-implementations)))
   (let [router-ctor (if (fn? router-type)
                       router-type
                       (router-type router-implementations))]
     (router-spec routing-table router-ctor))))

(defn- attach-bad-request-response
  [context exception]
  (assoc context :response
         {:status 400
          :headers {}
          :body (str "Bad Request - " (.getMessage exception))}))

(def query-params
  "Returns an interceptor which parses query-string parameters from an
  HTTP request into a map. Keys in the map are query-string parameter
  names, as keywords, and values are strings. The map is assoc'd into
  the request at :query-params."
  ;; This doesn't need to be a function but it's done that way for
  ;; consistency with 'method-param'
  (interceptor/interceptor
    {:name ::query-params
     :enter (fn [ctx]
              (try
                (update ctx :request parse-query-params)
                (catch IllegalArgumentException e
                  (attach-bad-request-response ctx e))))}))

(def path-params-decoder
  "An Interceptor which URL-decodes path parameters.
  The path parameters are in the :request map as :path-parameters.

  This will only operate once per interceptor chain execution, even if
  it appears multiple times; this prevents failures in existing applications
  that upgrade to Pedestal 0.6.0, as prior releases incorrectly failed to
  parse path parameters. Existing applications that upgrade may have
  this interceptor in some routes, which could yield runtime exceptions
  and request failures if the interceptor is executed twice."
  (interceptor/interceptor
   {:name ::path-params-decoder
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
  the request. Must come *after* the interceptor that populates that
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
         {:name ::method-param
          :enter (fn [ctx]
                   (update ctx :request #(replace-method param-path %)))}))))

(defn form-action-for-routes
  "Like 'url-for-routes' but the returned function returns a map with the keys
  :action, the URL string; and :method, the HTTP verb as a lower-case
  string. Also, the :method-param is :_method by default, so HTTP
  verbs other than GET and POST will be converted to POST with the
  actual verb in the query string."
  [routes & default-options]
  (let [{:as default-opts} default-options
        m (linker-map routes)]
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
  "Prints a route table (from [[expand-routes]]) in an easier to read format."
  [expanded-routes]
  (internal/print-routing-table expanded-routes))

(defn try-routing-for
  "Used for testing; constructs a router from the routing-table and router-type and perform routing, returning the matched
  route (from the expanded routes), or nil if routing was unsuccessful."
  [routing-table router-type path verb]
  (let [router  (router routing-table router-type)
        context {:request {:path-info path
                           :request-method verb}}
        context ((:enter router) context)]
    (:route context)))


(defmacro routes-from
  "Wraps around an expression that provides the routing specification.

 In production mode (the default) evaluates to the routing expression, unchanged.

 In development mode (see [[dev-mode?]]), evaluates to a function that, when invoked, returns the routing expression
 passed through [[expand-routes]]; this
 is to support a REPL workflow. This works in combination with the extension of [[RouterSpecification]]
 onto Fn, which requires that the returned routing specification be expanded.

 Further, when the expression is a non-local symbol, it is assumed to identify a Var holding the unexpanded routing specification;
 to avoid capturing the Var's value, the expansion de-references the named Var before passing it to expand-routes."
  {:added "0.7.0"}
  [route-spec-expr]
  (cond
    (not dev-mode?)
    route-spec-expr

    (and (symbol? route-spec-expr)
         (not (contains? &env route-spec-expr)))
    `(let [*prior-routes# (atom nil)]
       (fn [] (->> (var ~route-spec-expr)
                   deref
                   expand-routes
                   (internal/print-routing-table-on-change *prior-routes#))))

    ;; Either an inline route, a reference to a local symbol, or a function call.
    :else
    `(let [*prior-routes# (atom nil)]
       (fn [] (->> ~route-spec-expr
                   expand-routes
                   (internal/print-routing-table-on-change *prior-routes#))))))

