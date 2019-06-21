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

(ns io.pedestal.http.route
  (:require [clojure.string :as str]
            [clojure.core.incubator :refer [dissoc-in]]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as interceptor.chain]
            [io.pedestal.log :as log]
            [io.pedestal.http.route.definition :as definition]
            [io.pedestal.http.route.definition.terse :as terse]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.http.route.router :as router]
            [io.pedestal.http.route.linear-search :as linear-search]
            [io.pedestal.http.route.map-tree :as map-tree]
            [io.pedestal.http.route.prefix-tree :as prefix-tree])
  (:import (java.net URLEncoder URLDecoder)))


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
  [string]
  (URLDecoder/decode string "UTF-8"))

(defn encode-query-part
  "Encodes one key or value for a UTF-8 URL-encoded query string.
  Encodes space as +."
  [string]
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

;;; Combined matcher & request handler

(defn- replace-method
  "Replace the HTTP method of a request with the value provided at
  param-path (if provided). Removes the value found at param-path."
  [param-path request]
  (let [{:keys [request-method]} request]
    (if-let [method (get-in request param-path)]
      (-> request
          (assoc :request-method (keyword method))
          (dissoc-in param-path))
      request)))

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
        (update-in [:path-params] #(merge (:path-params request) params %))
        (update-in [:query-params]
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
                       ;;(concat context-path-parts path-parts)
                       (cond
                         (and context-path-parts (empty? (first path-parts))) (concat context-path-parts (rest path-parts))
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

     :absolute?     Boolean, whether or not to force an absolute URL

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
    - The incoming request being routed."
  [route-name & options]
  (if *url-for*
    (apply (if (delay? *url-for*) (deref *url-for*) *url-for*)
           route-name options)
    (throw (ex-info "*url-for* not bound" {}))))

(defprotocol ExpandableRoutes
  (-expand-routes [expandable-route-spec]
                  "Generate and return the routing table from a given expandable
                  form of routing data."))

(extend-protocol ExpandableRoutes
  clojure.lang.APersistentVector
  (-expand-routes [route-spec]
    (terse/terse-routes route-spec))

  clojure.lang.APersistentMap
  (-expand-routes [route-spec]
    (-expand-routes [[(terse/map-routes->vec-routes route-spec)]]))

  clojure.lang.APersistentSet
  (-expand-routes [route-spec]
    (table/table-routes route-spec)))

(defn expand-routes
  "Given a value (the route specification), produce and return a sequence of
  route-maps -- the expanded routes from the specification.

  Ensure the integrity of the sequence of route maps (even if they've already been checked).
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
  (router-spec [specification router-ctor]
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
  clojure.lang.Sequential
  (router-spec [seq router-ctor]
    (let [router (router-ctor seq)]
      (interceptor/interceptor
        {:name ::router
         :enter #(route-context % router seq)})))

  clojure.lang.Fn
  (router-spec [f router-ctor]
    ;; Caution: This could be very slow becuase it has to build the routing data
    ;; structure every time it routes a request.
    ;; This is only intended if you wanted to dynamically dispatch in a dynamic router
    ;; or completely control all routing aspects.
    (interceptor/interceptor
      {:name ::router
       :enter (fn [context]
                (let [routes (f)
                      router (router-ctor routes)]
                  (route-context context router routes)))})))

(def router-implementations
  {:map-tree map-tree/router
   :prefix-tree prefix-tree/router
   :linear-search linear-search/router})

(defn router
  "Delegating fn for router-specification."
  ([spec]
   (router spec :map-tree))
  ([spec router-impl-key-or-fn]
   (assert (or (contains? router-implementations router-impl-key-or-fn)
               (fn? router-impl-key-or-fn))
           (format "No router implementation exists for key %s. Please use one of %s."
                   router-impl-key-or-fn
                   (keys router-implementations)))
   (let [router-ctor (if (fn? router-impl-key-or-fn)
                       router-impl-key-or-fn
                       (router-impl-key-or-fn router-implementations))]
     (router-spec spec router-ctor))))

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
                (update-in ctx [:request] parse-query-params)
                (catch IllegalArgumentException iae
                  (interceptor.chain/terminate
                    (assoc ctx :response {:status 400
                                          :body (str "Bad Request - " (.getMessage iae))})))))}))

(def path-params-decoder
  "An Interceptor which URL-decodes path parameters."
  (interceptor/interceptor
   {:name ::path-params-decoder
    :enter (fn [ctx]
             (try
               (update-in ctx [:request] parse-path-params)
               (catch IllegalArgumentException iae
                 (interceptor.chain/terminate
                  (assoc ctx :response {:status 400
                                        :body (str "Bad Request - " (.getMessage iae))})))))}))

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
                   (update-in ctx [:request] #(replace-method param-path %)))}))))

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
  "Prints route table `routes` in easier to read format."
  [routes]
  (doseq [r (map (fn [{:keys [method path route-name]}] [method path route-name]) routes)]
    (println r)))

(defn try-routing-for [spec router-type query-string verb]
  (let [router  (router spec router-type)
        context {:request {:path-info query-string
                           :request-method verb}}
        context ((:enter router) context)]
    (:route context)))
