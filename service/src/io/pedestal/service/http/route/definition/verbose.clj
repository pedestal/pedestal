(ns io.pedestal.service.http.route.definition.verbose
  (:require [io.pedestal.service.http.route :as route]
            [io.pedestal.service.interceptor :as interceptor]))

(defprotocol HandlerFn
  (handler-interceptor [handler-fn name] "Return a io.pedestal.service.interceptor from `handler-fn`"))

(extend-protocol HandlerFn
  io.pedestal.service.impl.interceptor.Interceptor
  (handler-interceptor [{interceptor-name :name :as interceptor} name]
    (assoc interceptor :name (or interceptor-name name)))

  clojure.lang.IFn
  (handler-interceptor [handler-fn name]
    (interceptor/handler name handler-fn)))


(defprotocol InterceptorSpecification
  (resolve-interceptor [interceptor-specification name] "Return an interceptor derived from specification."))

(extend-protocol InterceptorSpecification
  clojure.lang.Symbol
  (resolve-interceptor [symbol name]
    (if (-> (resolve symbol)
            meta
            :interceptor-fn)
      (handler-interceptor ((eval symbol)) name)
      (handler-interceptor (eval symbol) name)))

  clojure.lang.ISeq
  (resolve-interceptor [list name]
    (handler-interceptor (eval list) name))

  io.pedestal.service.impl.interceptor.Interceptor
  (resolve-interceptor [interceptor name]
    (handler-interceptor interceptor name)))

(defn symbol->keyword
  [s]
  (let [{ns :ns n :name} (meta (resolve s))]
    (keyword (name (ns-name ns)) (name n))))

(defprotocol HandlerSpecification
  (handler-map [handler-specification] "Return a handler map derived from specification."))

(extend-protocol HandlerSpecification
  clojure.lang.Symbol
  (handler-map [symbol]
    (let [handler-name (symbol->keyword symbol)]
      {:route-name handler-name
       :handler (resolve-interceptor symbol handler-name)}))

  clojure.lang.APersistentMap
  (handler-map [{:keys [route-name handler interceptors] :as m}]
    (let [handler-name (cond
                        (symbol? handler) (symbol->keyword handler)
                        (interceptor/interceptor? handler) (:name handler))
          interceptor (resolve-interceptor handler (or route-name handler-name))
          interceptor-name (:name interceptor)]
      {:route-name (if route-name
                     route-name
                     (if interceptor-name
                       interceptor-name
                       (throw (ex-info "Handler specification was not a symbol or an interceptor with a name, no route name provided"
                                       {:handler-spec m}))))
       :handler (resolve-interceptor handler (or route-name handler-name))
       :interceptors (vec (map #(resolve-interceptor % nil) interceptors))})))

(defn add-terminal-info
  "Merge in data from `handler-map` to `start-terminal`"
  [{:keys [interceptors] :as start-terminal}
   {new-interceptors :interceptors :or {:interceptors []} :as handler-map}]
  (merge start-terminal
         {:interceptors (-> interceptors
                            (into new-interceptors)
                            (conj (:handler handler-map)))
          :route-name (:route-name handler-map)}))

(defn generate-verb-terminal
  "Return a new route table entry based on `dna` `path`, and a vector
  of `[verb handler]` pairs."
  [dna [verb handler]]
  (-> dna
      (merge {:method verb})
      (route/merge-path-regex)
      (merge dna)
      (add-terminal-info (handler-map handler))))

(defn capture-constraint
  "Add parenthesis to a regex in order to capture it's value during evaluation."
  [[k v]] [k (str "(" v ")")])

(defn update-constraints
  "Return a new DNA based on the contents of `dna` and
  `constraints`. Constraints are added to path-constraints if no verbs
  are defined in the current DNA, and are sorted and added to
  path-constraints and query-constraints, depending on whether the
  constraint's key identifies a path-param."
  [{path-params :path-params :as dna} constraints verbs]
  (if (empty? verbs)
    (update-in dna [:path-constraints] merge (map capture-constraint constraints))
    (let [path-param? (fn [[k _]] (some #{k} path-params))
          [path-constraints query-constraints] ((juxt filter remove) path-param? constraints)]
      (-> dna
          (update-in [:path-constraints] merge (into {} (map capture-constraint path-constraints)))
          (update-in [:query-constraints] merge query-constraints)))))


(defn update-dna
  "Return new DNA based on the contents of `parent-dna` and
  `current-node`"
  [{^String parent-path :path :as parent-dna}
   {:keys [constraints verbs interceptors path] :as current-node}]
  (cond-> parent-dna
          true (merge (select-keys current-node [:app-name :scheme :host]))
          path (route/parse-path path)
          ;; special case case where parent-path is "/" so we don't have double "//"
          path (assoc :path (str (if (and parent-path (.endsWith parent-path "/"))
                                   (subs parent-path 0 (dec (count parent-path))) parent-path)
                                 path))
          constraints (update-constraints constraints verbs)
          interceptors (update-in [:interceptors]
                                  into
                                  (map #(resolve-interceptor % nil) interceptors))))


(defn generate-route-entries
  "Return a list of route table entries based on the treeish structure
  of `route-map` and `dna`"
  [dna {:keys [path verbs children] :as route-map}]
  (let [current-dna (update-dna dna route-map)]
    (concat (map (partial generate-verb-terminal current-dna) verbs)
            (mapcat (partial generate-route-entries current-dna) children))))

(defn uniquely-add-route-path
  "Append `route-path` to `route-paths` if route-paths doesn't contain it
  already."
  [route-paths route-path]
  (if (some #{route-path} route-paths)
    route-paths
    (conj route-paths route-path)))

(defn sort-by-constraints
  "Sort the grouping of route entries whcih all correspond to
  `route-path` from `groupings` such that the most constrained route
  table entries appear first and the least constrained appear last."
  [groupings route-path]
  (let [grouping (groupings route-path)]
    (sort-by (comp - count :query-constraints) grouping)))

(defn prioritize-constraints
  "Sort a flat routing table of entries to guarantee that the most
  constrained route entries appear in the table prior to entries which
  have fewer constraints or no constraints."
  [routing-table]
  (let [route-paths (map #(map % [:app-name :scheme :host :path-parts])
                                        routing-table)
        unique-route-paths (reduce uniquely-add-route-path [] route-paths)
        groupings (group-by #(map % [:app-name :scheme :host :path-parts])
                            routing-table)]
    (mapcat (partial sort-by-constraints groupings) unique-route-paths)))

(def default-dna
  {:path-parts []
   :path-params []
   :interceptors []})

(defn verify-unique-route-names
  [routing-table]
  (let [non-unique-names (->> routing-table
                              (group-by :route-name)
                              (map (fn [[k v]] [k (count v)]))
                              (filter (fn [[_ v]] (> v 1)))
                              (map first))]
    (when (seq non-unique-names)
      (throw (ex-info "Route names are not unique"
                      {:non-unique-names non-unique-names})))
    routing-table))

(defn expand-verbose-routes
  "Expand route-maps into a routing table of route entries."
  [route-maps]
  (->> route-maps
       (mapcat (partial generate-route-entries default-dna))
       prioritize-constraints
       #_verify-unique-route-names))

(defmacro defroutes
  "Define a routing table from a collection of route maps."
  [name route-maps]
  `(def ~name (quote ~(expand-verbose-routes route-maps))))

