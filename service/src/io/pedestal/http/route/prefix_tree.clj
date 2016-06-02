; Copyright 2015-2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.prefix-tree
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [io.pedestal.http.route.router :as router]))

;; The Node record is only used as a faster map

(defrecord Node [wild? catch-all? segment param payload children])

;; The Payload record is just a marker used while constructing the
;; tree. Before the tree is used it is replaced with a function which
;; will find a route based on method, host, scheme and port.

(defrecord Payload [routes])

(defn- char-key
  "Return the single character child key for the string started at
  index i."
  [s i]
  (when (< i (count s))
    (subs s i (inc i))))

(defn- wild? [s]
  (contains? #{\: \*} (first s)))

(defn- wild-param?
  "Return true if a string segment starts with a wildcard string."
  [segment]
  (= \: (first segment)))

(defn- catch-all-param?
  "Return true if a string segment starts with a catch-all string."
  [segment]
  (= \* (first segment)))

(defn partition-wilds
  "Given a path-spec string, return a seq of strings with wildcards
  and catch-alls separated into their own strings. Eats the forward
  slash following a wildcard."
  [path-spec]
  (let [groups (partition-by wild? (str/split path-spec #"/"))
        first-groups (butlast groups)
        last-group (last groups)]
    (flatten
     (conj (mapv #(if (wild? (first %))
                    %
                    (str (str/join "/" %) "/"))
                 first-groups)
           (if (wild? (first last-group))
             last-group
             (str/join "/" last-group))))))

(comment

  (partition-wilds "/foo/bar")
  ;;=> ("/foo/bar")

  (partition-wilds "/foo/:bar")
  ;;=> ("/foo/" ":bar")

  (partition-wilds "/foo/:bar/baz")
  ;;=> ("/foo/" ":bar" "baz")

  (partition-wilds "/foo/*baz")
  ;;=> ("/foo/" "*baz")

  )

(defn contains-wilds?
  "Return true if the given path-spec contains any wildcard params or
  catch-alls."
  [path-spec]
  (let [parts (partition-wilds path-spec)]
    (or (> (count parts) 1)
        (wild? (first parts)))))

(defn- make-node
  "Given a path-spec segment string and a payload object, return a new
  tree node."
  [segment o]
  (map->Node
   (cond (wild-param? segment)
         {:wild? true
          :segment segment
          :param (keyword (subs segment 1))
          :payload (when o (->Payload [o]))}

         (catch-all-param? segment)
         {:catch-all? true
          :segment segment
          :param (keyword (subs segment 1))
          :payload (when o (->Payload [o]))}

         :else
         {:segment segment
          :payload (when o (->Payload [o]))})))

(defn- add-child
  "Given a tree node, a single char string key and a child node,
  return a new tree where this node has this child."
  [node key child]
  (assoc-in node [:children key] child))

(declare insert)

(defn- insert-child
  "Given a tree node, a single char string key, a path-spec string and
  a payload object, return a tree where this object has been instered
  at path-spec under this node."
  [node key path-spec o]
  (update-in node [:children key] insert path-spec o))

(defn- new-node
  "Given a path-spec and a payload object, return a new tree node. If
  the path-spec contains wildcards or catch-alls, will return parent
  node of a tree (linked list)."
  [path-spec o]
  (if (contains-wilds? path-spec)
    (let [parts (partition-wilds path-spec)]
      (reduce (fn [child segment]
                (when (catch-all-param? segment)
                  (throw (ex-info "catch-all may only appear at the end of a path spec"
                                  {:patch-spec path-spec})))
                (-> (make-node segment nil)
                    (add-child (subs (:segment child) 0 1) child)))
              (let [segment (last parts)]
                (make-node segment o))
              (reverse (butlast parts))))
    (make-node path-spec o)))

(defn- calc-lcs
  "Given two strings, return the end index of the longest common
  prefix string."
  [s1 s2]
  (loop [i 1]
    (cond (or (< (count s1) i)
              (< (count s2) i))
          (dec i)

          (= (subs s1 0 i)
             (subs s2 0 i))
          (recur (inc i))

          :else (dec i))))

(comment

  (calc-lcs "/foo/bar" "/foo/baz")
  ;;=> 7

  (subs "/foo/bar" (calc-lcs "/foo/bar" "/foo/baz"))
  ;;=> "r"

  )

(defn- split
  "Given a node, a path-spec, a payload object to insert into the tree
  and the lcs, split the node and return a new parent node with the
  old contents of node and the new item as children.

  lcs is the index of the longest common string in path-spec and the
  segment of node."
  [node path-spec o lcs]
  (let [segment (:segment node)
        common (subs path-spec 0 lcs)
        parent (new-node common nil)]
    (if (= common path-spec)
      (-> (assoc parent :payload (when o (->Payload [o])))
          (add-child (char-key segment lcs)
                     (update-in node [:segment] subs lcs)))
      (-> parent
          (add-child (char-key segment lcs)
                     (update-in node [:segment] subs lcs))
          (insert-child (char-key path-spec lcs) (subs path-spec lcs) o)))))

(defn insert
  "Given a tree node, a path-spec and a payload object, return a new
  tree with payload inserted."
  [node path-spec o]
  (let [segment (:segment node)]
    (cond (nil? node)
          (new-node path-spec o)

          (= segment path-spec)
          (if (:payload node)
            (assoc node :payload (->Payload ((fnil conj []) (:routes (:payload node)) o)))
            (assoc node :payload (when o (->Payload [o]))))

          ;; handle case where path-spec is a wildcard param
          (wild-param? path-spec)
          (let [lcs (calc-lcs segment path-spec)
                common (subs path-spec 0 lcs)]
            (if (= common segment)
              (let [path-spec (subs path-spec (inc lcs))]
                (insert-child node (subs path-spec 0 1) path-spec o))
              (throw (ex-info "route conflict"
                              {:node node
                               :path-spec path-spec
                               :segment segment}))))

          ;; in the case where path-spec is a catch-all, node should always be nil.
          ;; getting here means we have an invalid route specification
          (catch-all-param? path-spec)
          (throw (ex-info "route conflict"
                          {:node node
                           :path-spec path-spec
                           :segment segment}))

          :else
          (let [lcs (calc-lcs segment path-spec)]
            (cond (= lcs (count segment))
                  (insert-child node (char-key path-spec lcs) (subs path-spec lcs) o)

                  :else
                  (split node path-spec o lcs))))))

(defn- result-map
  "Construct and return a lookup result."
  ([node path-params]
     {:path-params path-params
      :payload (:payload node)})
  ([node path-params path]
     {:path-params (assoc path-params (:param node) path)
      :payload (:payload node)}))

(defn- get-child
  "Given a node, a request path and a segment size (the lcs index of
  node's segment and path) return the child node which will get us one
  step closer to finding a match.

  If a wildcard or catch-all child exist then they will be the only
  possible child."
  [node path segment-size]
  (let [c (:children node)]
    (or (get c ":")
        (get c "*")
        (get c (char-key path segment-size)))))

(defn lookup
  "Given a tree node and request path, find a matching leaf node and
  return the path params and payload or return nil if no match is
  found. Returns a map with :path-params and :payload keys."
  [node ^String path]
  (loop [path-params {}
         node node
         path path]
    (let [segment (:segment node)]
      (cond
        (or (nil? node) (empty? path))
        nil

        (= segment path)
        (result-map node path-params)

        (:wild? node)
        (let [i (.indexOf path "/")]
          (if (pos? i)
            (let [value (subs path 0 i)]
              (recur (assoc path-params (:param node) value)
                     (get-child node path (inc i))
                     (subs path (inc i))))
            (result-map node path-params path)))

        (:catch-all? node)
        (result-map node path-params path)

        :else
        (let [segment-size (count segment)
              p (when (>= (count path) segment-size) (subs path 0 segment-size))]
          (when (= segment p)
            (recur path-params (get-child node path segment-size) (subs path segment-size))))))))

(comment

  (def ptree (-> nil
                 (insert "/foo/bar" 1)
                 (insert "/foo/baz" 2)
                 (insert "/foo/bar/:x" 3)
                 (insert "/foo/bar/:x" 4)
                 (insert "/foo/baz/*rest" 5)))

  (time (:path-params (lookup ptree "/foo/bar")))
  ;;=> {}
  (time (:path-params (lookup ptree "/foo/bar/baz")))
  ;;=> {:x "baz"}
  (time (:path-params (lookup ptree "/foo/baz/one/two/three")))
  ;;=> {:rest "one/two/three"}

  (def ptree (-> nil
                 (insert "/:foo/bar" 1)
                 (insert "/:foo/baz" 2)
                 (insert "/:foo/bar/:x" 3)
                 (insert "/:foo/bar/:x" 4)
                 (insert "/:foo/baz/*rest" 5)))

  (:path-params (lookup ptree "/foo/bar"))
  ;;=> {:foo "foo"}
  (:path-params (lookup ptree "/foo/bar/baz"))
  ;;=> {:x "baz", :foo "foo"}
  (:path-params (lookup ptree "/foo/baz/one/two/three"))
  ;;=> {:rest "one/two/three", :foo "foo"}

  )

(defrecord PrefixTreeRouter [routes tree]
  router/Router
  (find-route [this req]
    ;; find a result in the prefix-tree - payload could contains mutiple routes
    (when-let [{:keys [payload] :as result} (lookup tree (:path-info req))]
      ;; call payload function to find specific match based on method, host, scheme and port
      (when-let [route (when payload (payload req))]
        ;; return a match only if path and query constraints are satisfied
        (when ((::satisfies-constraints? route) req (:path-params result))
          (assoc route :path-params (:path-params result)))))))

;; The prefix tree is used to find a collection of routes which are
;; indexed by method, host, scheme and port, in that order. This is
;; not part of the prefix tree because different logic is used. Here
;; we find the best match. We traverse a nested map and at each level
;; look for the specific value or ::any.

(defn- wild-path
  "Given a route, create a key path which will be used to insert this
  route into a nested map. Use ::any to indicate that we match any
  value."
  [{:keys [method host scheme port] :as route-map}]
  [(if (not= method :any) method ::any)
   (or host ::any)
   (or scheme ::any)
   (or port ::any)])

(defn- matcher-preds
  "Given a route, return a seq of predicate functions which, when
  passed a request, will return true if the request and route have
  matching method, scheme, host and port."
  [route]
  (let [{:keys [method scheme host port]} route]
    (remove nil?
            [(when (and method (not= method :any)) #(= method (:request-method %)))
             (when host #(= host (:server-name %)))
             (when port #(= port (:server-port %)))
             (when scheme #(= scheme (:scheme %)))])))

(defn- best-match [m [first & rest]]
  (if first
    (or (best-match (get m first) rest)
        (best-match (::any m) rest))
    m))

(comment

  (best-match {:x {:y {:z 42}}}
              [:x :y :z])
  ;;=> 42

  (best-match {:x    {:y    {:a    nil}
                      ::any {:c    nil}}
               ::any {:y    {:b    nil}
                      ::any {:d    nil
                             ::any 42}}}
              [:x :y :z])
  ;;=> 42

  )

;; The payload that we find in the prefix tree is a function of the
;; request. We call this function to get the route or nil. The
;; function below creates the payload function.

(defn create-payload-fn
  "Given a sequence of routes, return a function of a request which
  will return a matching route. When the returned function is called
  we already know that the path matches. The function only considers
  method, host, scheme and port and will return the most specific
  match."
  [routes]
  (cond (= (count routes) 1)
        (let [route (first routes)
              path (wild-path route)]
          (if (= path [::any ::any ::any ::any])
            ;; there is only one route which matches any method, host, scheme and port
            (constantly route)
            ;; there is only one route which has some match criteria,
            ;; build a predicate function which returns true if it matches
            (let [match? (let [preds (matcher-preds route)]
                           (if (seq preds)
                             (apply every-pred preds)
                             (constantly true)))]
              (fn [request]
                (when (match? request)
                  route)))))

        (every? #(= % [::any ::any ::any])
                (map (comp rest wild-path) routes))
        ;; there is more than one route, but they only differ by
        ;; method (a common case), find a match with a single lookup
        (let [m (reduce (fn [acc {:keys [method] :as route}]
                          (let [method-key (if (= method :any) ::any method)]
                            (assoc acc method-key route)))
                        {}
                        routes)]
          (fn [request]
            (or (get m (:request-method request))
                (get m ::any))))

        :else
        ;; default case, lookup route using method, host, scheme and port
        (let [subtree (reduce (fn [t route]
                                (let [path (wild-path route)]
                                  (if (get-in t path)
                                    (throw (ex-info "duplicate route spec"
                                                    {:route route}))
                                    (assoc-in t (wild-path route) route))))
                              {}
                              routes)
              path [:request-method :server-name :scheme :server-port]]
          (fn [request]
            (best-match subtree (map #(% request) path))))))

(defn- optimize-payloads
  "Given a prefix tree which contains Payload nodes, return a tree
  with Payload nodes replaced by functions of the request which return
  a route."
  [tree]
  (walk/postwalk (fn [node]
                   (if (= (type node) Payload)
                     (create-payload-fn (:routes node))
                     node))
                 tree))

(defn- satisfies-query-constraints
  "Given a map of query constraints, return a predicate function of
  the request which will return true if the request satisfies the
  constraints."
  [query-constraints]
  (fn [request]
    (let [params (:query-params request)]
      (every? (fn [[k re]]
                (and (contains? params k)
                     (re-matches re (get params k))))
              query-constraints))))

(defn- satisfies-path-constraints
  "Given a map of path constraints, return a predicate function of
  the request which will return true if the request satisfies the
  constraints."
  [path-constraints]
  (let [path-constraints (zipmap (keys path-constraints)
                                 (mapv #(re-pattern %) (vals path-constraints)))]
    (fn [path-params]
      (every? (fn [[k re]]
                (and (contains? path-params k)
                     (re-matches re (get path-params k))))
              path-constraints))))

(defn add-satisfies-constraints?
  "Given a route, add a function of the request which returns true if
  the request satisfies all path and query constraints."
  [{:keys [query-constraints path-constraints] :as route}]
  (let [qc? (satisfies-query-constraints query-constraints)
        pc? (satisfies-path-constraints path-constraints)
        satisfies-constraints? (cond (and query-constraints path-constraints)
                                     (fn [request path-params]
                                       (and (qc? request) (pc? path-params)))
                                     query-constraints
                                     (fn [request _]
                                       (qc? request))
                                     path-constraints
                                     (fn [_ path-params]
                                       (pc? path-params))
                                     :else
                                     (constantly true))]
    (assoc route ::satisfies-constraints? satisfies-constraints?)))

(defn router
  "Given a sequence of routes, return a router which satisfies the
  io.pedestal.http.route.router/Router protocol."
  [routes]
  (let [tree (->> (map add-satisfies-constraints? routes)
                  (reduce (fn [tree route]
                            (insert tree (:path route) route))
                          nil)
                  optimize-payloads)]
    (->PrefixTreeRouter routes tree)))

(comment

  (def my-router (router [{:path "/foo/:x"}
                          {:path "/foo/:x/bar/*rest"}]))

  (:path-params (router/find-route my-router {:path-info "/foo/bar"}))
  ;;=> {:x "bar"}

  (:path-params (router/find-route my-router {:path-info "/foo/bar/bar/one/two"}))
  ;;=> {:rest "one/two", :x "bar"}

  )

