; Copyright 2013 Relevance, Inc.
; Copyright 2014-2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.definition.verbose
  (:require [io.pedestal.http.route.definition :as definition]
            [io.pedestal.http.route.path :as path]
            [io.pedestal.interceptor :refer [interceptor?]]
            [io.pedestal.interceptor.helpers :as interceptor]
            [clojure.string :as string]))

(defn handler-interceptor
  [handler name]
  (cond
    (interceptor? handler) (let [{interceptor-name :name :as interceptor} handler]
                             (assoc interceptor :name (or interceptor-name name)))
    (fn? handler) (interceptor/handler name handler)))


(defn resolve-interceptor [interceptor name]
  (if (interceptor? interceptor)
    (handler-interceptor interceptor name)
    (handler-interceptor (io.pedestal.interceptor/interceptor interceptor) name)

   ;(symbol? interceptor) (if (-> (resolve interceptor)
   ;                              fn?)
   ;                        (handler-interceptor ((resolve interceptor)) name)
   ;                        (handler-interceptor @(resolve interceptor) name))
   ;(seq? interceptor) (handler-interceptor (eval interceptor) name)

   ))



(defn handler-map [m]
  (cond
   (symbol? m)
   (let [handler-name (definition/symbol->keyword m)]
     {:route-name handler-name
      :handler (resolve-interceptor m handler-name)})
   ;(isa? (type m) clojure.lang.APersistentMap)
   (instance? clojure.lang.IPersistentMap m)
   (let [{:keys [route-name handler interceptors]} m
         handler-name (cond
                       (symbol? handler) (definition/symbol->keyword handler)
                       (interceptor? handler) (:name handler))
         interceptor (resolve-interceptor handler (or route-name handler-name))
         interceptor-name (:name interceptor)]
     {:route-name (if route-name
                    route-name
                    (if interceptor-name
                      interceptor-name
                      (throw (ex-info "Handler was not symbol or interceptor with name, no route name provided"
                                      {:handler-spec m}))))
      :handler (resolve-interceptor handler (or route-name handler-name))
      :interceptors (mapv #(resolve-interceptor % nil) interceptors)})))

(defn- add-terminal-info
  "Merge in data from `handler-map` to `start-terminal`"
  [{:keys [interceptors] :as start-terminal}
   {new-interceptors :interceptors :or {new-interceptors []} :as handler-map}]
  (merge start-terminal
         {:interceptors (-> interceptors
                            (into new-interceptors)
                            (conj (:handler handler-map)))
          :route-name (:route-name handler-map)}))

(defn- generate-verb-terminal
  "Return a new route table entry based on `dna` `path`, and a vector
  of `[verb handler]` pairs."
  [dna [verb handler]]
  (-> dna
      (merge {:method verb})
      (path/merge-path-regex)
      (merge dna)
      (add-terminal-info (handler-map handler))))

(defn- update-constraints
  "Return a new DNA based on the contents of `dna` and
  `constraints`. Constraints are added to path-constraints if no verbs
  are defined in the current DNA, and are sorted and added to
  path-constraints and query-constraints, depending on whether the
  constraint's key identifies a path-param."
  [{path-params :path-params :as dna} constraints verbs]
  (if (empty? verbs)
    (update-in dna [:path-constraints] merge (map definition/capture-constraint constraints))
    (let [path-param? (fn [[k _]] (some #{k} path-params))
          [path-constraints query-constraints] ((juxt filter remove) path-param? constraints)]
      (-> dna
          (update-in [:path-constraints] merge (into {} (map definition/capture-constraint path-constraints)))
          (update-in [:query-constraints] merge query-constraints)))))

(defn undoubleslash
  [^String s]
  (string/replace s #"/+" "/"))

(defn path-join
  [parent-path path]
  (str parent-path "/" path))

(defn- update-dna
  "Return new DNA based on the contents of `parent-dna` and
  `current-node`"
  [{^String parent-path :path :as parent-dna}
   {:keys [constraints verbs interceptors path] :as current-node}]
  (cond-> parent-dna
          true (merge (select-keys current-node [:app-name :scheme :host :port]))
          path (path/parse-path path)
          ;; special case case where parent-path is "/" so we don't have double "//"
          path (assoc :path (undoubleslash (path-join parent-path path)))
          constraints (update-constraints constraints verbs)
          interceptors (update-in [:interceptors]
                                  into
                                  (map #(resolve-interceptor % nil) interceptors))))

(defn- generate-route-entries
  "Return a list of route table entries based on the treeish structure
  of `route-map` and `dna`"
  [dna {:keys [path verbs children] :as route-map}]
  (let [current-dna (update-dna dna route-map)]
    (concat (map (partial generate-verb-terminal current-dna) verbs)
            (mapcat (partial generate-route-entries current-dna) children))))

(def default-dna
  {:path-parts []
   :path-params []
   :interceptors []})

(defn expand-verbose-routes
  "Expand route-maps into a routing table of route entries."
  [route-maps]
  (->> route-maps
       (mapcat (partial generate-route-entries default-dna))
       definition/ensure-routes-integrity))

