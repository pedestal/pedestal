; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service.http.route
  (:require [clojure.string :as str]
            [clojure.core.incubator :refer (dissoc-in)]
            [io.pedestal.service.interceptor :as interceptor :refer [definterceptor definterceptorfn]]
            [io.pedestal.service.impl.interceptor :as interceptor-impl]
            [io.pedestal.service.log :as log])
  (:import (java.util.regex Pattern)
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

;;; Parsing pattern strings to match URI paths

(defn- parse-path-token [out string]
  (condp re-matches string
    #"^:(.+)$" :>> (fn [[_ token]]
                     (let [key (keyword token)]
                       (-> out
                           (update-in [:path-parts] conj key)
                           (update-in [:path-params] conj key)
                           (assoc-in [:path-constraints key] "([^/]+)"))))
    #"^\*(.+)$" :>> (fn [[_ token]]
                      (let [key (keyword token)]
                        (-> out
                            (update-in [:path-parts] conj key)
                            (update-in [:path-params] conj key)
                            (assoc-in [:path-constraints key] "(.*)"))))
    (update-in out [:path-parts] conj string)))

(defn parse-path
  ([pattern] (parse-path {:path-parts [] :path-params [] :path-constraints {}} pattern))
  ([accumulated-info pattern]
     (if-let [m (re-matches #"/(.*)" pattern)]
       (let [[_ path] m]
         (reduce parse-path-token
                 accumulated-info
                 (str/split path #"/")))
       (throw (ex-info "Invalid route pattern" {:pattern pattern})))))

(defn path-regex [route]
  (let [{:keys [path-parts path-constraints]} route
        path-parts (if (and (> (count path-parts) 1)
                            (empty? (first path-parts)))
                     (rest path-parts)
                     path-parts)]
    (re-pattern
     (apply str
      (interleave (repeat "/")
                  (map #(or (get path-constraints %) (Pattern/quote %))
                       path-parts))))))

(defn merge-path-regex [route]
  (assoc route :path-re (path-regex route)))

(defn expand-route-path [route]
  (let [r (merge route (parse-path (:path route)))]
    (merge-path-regex r)))

(defn- path-matcher [route]
  (let [{:keys [path-re path-params]} route]
    (fn [req]
      (when req
       (when-let [m (re-matches path-re (:path-info req))]
         (zipmap path-params (rest m)))))))

(defn- matcher-components [route]
  (let [{:keys [method scheme host port path query-constraints]} route]
    (list (when (and method (not= method :any)) #(= method (:request-method %)))
          (when host   #(= host (:server-name %)))
          (when port   #(= port (:server-port %)))
          (when scheme #(= scheme (:scheme %)))
          (when query-constraints
            (fn [request]
              (let [params (:query-params request)]
                (every? (fn [[k re]]
                          (and (contains? params k)
                               (re-matches re (get params k))))
                        query-constraints)))))))

(defn- matcher [route]
  (let [base-matchers (remove nil? (matcher-components route))
        base-match (if (seq base-matchers)
                     (apply every-pred base-matchers)
                     (constantly true))
        path-match (path-matcher route)]
    (fn [request]
      (and (base-match request) (path-match request)))))

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

(defn parse-query-params [request]
  (if-let [string (:query-string request)]
    (assoc request :query-params (parse-query-string string))
    request))

;;; Combined matcher & request handler

(defn- enqueue-all [context interceptors]
  (apply interceptor-impl/enqueue context interceptors))

(defn- replace-method
  "If the request has a query-parameter (already parsed into the
  :query-params map) named method-param, set it to the :request-method
  of the request and remove from :query-params. Returns an updated
  request."
  [method-param request]
  (let [{:keys [request-method]} request]
    (if-let [method (get-in request [:query-params method-param])]
      (-> request
          (assoc :request-method (keyword method))
          (dissoc-in [:query-params method-param]))
      request)))

(definterceptor query-params
  "Returns an interceptor which parses query-string parameters from an
  HTTP request into a map. Keys in the map are query-string parameter
  names, as keywords, and values are strings. The map is assoc'd into
  the request at :query-params."
  ;; This doesn't need to be a function but it's done that way for
  ;; consistency with 'method-param'
  (interceptor/on-request ::query-params parse-query-params))

(definterceptorfn method-param
  "Returns an interceptor that smuggles HTTP verbs through a
  query-string parameter. Must come after query-params.
  method-param is the name of the query-string parameter as a keyword,
  defaults to :_method"
  ([]
     (method-param :_method))
  ([method-param]
     (interceptor/on-request ::method-param #(replace-method method-param %))))

(defn print-routes
  "Prints route table `routes` in easier to read format."
  [routes]
  (doseq [r (map (fn [{:keys [method path route-name]}] [method path route-name]) routes)]
    (println r)))

;;; Linker

(defn- merge-param-options
  "Merges the :params map into :path-params and :query-params. The
  :path-params keys are taken from the route, any other keys in
  :params are added to :query-params. Returns updated opts."
  [opts route]
  (let [{:keys [params]} opts]
    (-> opts
        (dissoc :params)
        (update-in [:path-params] #(merge params %))
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
  (log/info :in :context-path
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

(defn- link-str
  "Returns a string for a route. opts is a map as described in the
  docstring for 'url-for'."
  [route opts]
  (let [{:keys [path-params query-params request]} opts
        {:keys [scheme host port path-parts]} route
        context-path-parts (context-path opts)
        path-parts (do (log/info :in :link-str
                                 :path-parts path-parts
                                 :context-path-parts context-path-parts)
                       (if (and context-path-parts (empty? (first path-parts)))
                         (concat context-path-parts (rest path-parts))
                         path-parts))
        path (str/join \/ (map #(get path-params % %) path-parts))
        scheme-match (or (nil? scheme) (= scheme (:scheme request)))
        host-match (or (nil? host) (= host (:server-name request)))
        port-match (or (nil? port) (= port (:server-port request)))]
    (str
     (when-not scheme-match (str (name scheme) \:))
     (when-not (and scheme-match host-match port-match)
       (str "//" host (when port (str ":" port))))
     (str (when-not (.startsWith path "/") "/") path)
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

     :query-params  A map of query-string parameters only

     :method-param  Keyword naming the query-string parameter in which
                    to place the HTTP method name, if it is neither
                    GET nor POST. If nil, the HTTP method name will
                    not be included in the query string. Default is nil.

     :context       A string, function that returns a string, or symbol
                    that resolves to a function that returns a string
                    that specifies a root context for the URL. Default
                    is nil.

  In addition, you may supply default-options to the 'url-for-routes'
  function, which are merged with the options supplied to the returned
  function."
  [routes & default-options]
  (let [{:as default-opts} default-options
        m (linker-map routes)]
    (fn [route-name & options]
      (let [{:keys [app-name] :as options-map} options
            route (find-route m app-name route-name)
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
    (apply *url-for* route-name options)
    (throw (ex-info "*url-for* not bound" {}))))

(defprotocol RouterSpecification
  (router-spec [specification] "Returns an interceptor which attempts to match each route against
  a :request in context. For the first route that matches, it will:

    - enqueue the matched route's interceptors
    - associate the route into the context at :route
    - associate a map of :path-params into the :request

  If no route matches, returns context with :route nil."))

(defn- route-context-to-matcher-routes
  "Route context based on matcher-routes."
  [{:keys [request] :as context} matcher-routes routes]
  (or (some (fn [{:keys [matcher interceptors] :as route}]
              (when-let [path-params (matcher request)]
                (let [linker (url-for-routes routes :request request)]
                  (-> context
                      (assoc :route route
                             :request (assoc request :path-params path-params
                                             :url-for linker)
                             :url-for linker)
                      (assoc-in [:bindings #'*url-for*] linker)
                      (enqueue-all interceptors)))))
            matcher-routes)
      (assoc context :route nil)))

(extend-protocol RouterSpecification
  clojure.lang.Sequential
  (router-spec [seq]
    (let [matcher-routes (mapv #(assoc % :matcher (matcher %)) seq)]
      (interceptor/before ::router
                          #(route-context-to-matcher-routes % matcher-routes seq))))

  clojure.lang.Fn
  (router-spec [f]
    (interceptor/before ::router
                        (fn [context]
                          (let [routes (f)]
                            (route-context-to-matcher-routes context
                                                             (mapv #(assoc % :matcher (matcher %)) routes)
                                                             routes))))))

(defn router
  "Delegating fn for router-specification."
  [spec]
  (router-spec spec))

(definterceptor query-params
  "Returns an interceptor which parses query-string parameters from an
  HTTP request into a map. Keys in the map are query-string parameter
  names, as keywords, and values are strings. The map is assoc'd into
  the request at :query-params."
  ;; This doesn't need to be a function but it's done that way for
  ;; consistency with 'method-param'
  (interceptor/on-request ::query-params parse-query-params))

(definterceptorfn method-param
  "Returns an interceptor that smuggles HTTP verbs through a
  query-string parameter. Must come after query-params.
  method-param is the name of the query-string parameter as a keyword,
  defaults to :_method"
  ([]
     (method-param :_method))
  ([method-param]
     (interceptor/on-request ::method-param #(replace-method method-param %))))

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
