; Copyright 2024-2025 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:no-doc io.pedestal.http.route.sawtooth.impl
  {:added "0.8.0"}
  (:require [clojure.string :as string]
            [clj-commons.ansi :refer [perr]]))

(def ^:dynamic *squash-conflicts-report* false)

(defmacro with-split-path
  [path [path-term remaining-path] & body]
  `(let [^String path# ~path
         slashx#       (.indexOf path# "/")
         at-end?#      (neg? slashx#)
         ~path-term (if at-end?#
                      path#
                      (.substring path# 0 slashx#))
         ~remaining-path (when-not at-end?#
                           (.substring path# (inc slashx#)))]
     ~@body))

(defn- return-nil [_request] nil)

(defn- categorize-by
  [pred coll]
  (let [{true-values  true
         false-values false} (group-by pred coll)]
    [true-values false-values]))

(defn- path-part->term
  [path-part]
  (cond
    ;; This should only be at the end position
    ;; TODO: Does anything else ensure this?
    (string/starts-with? path-part "*")
    {:token    :wild
     :param-id (-> path-part (subs 1) keyword)}

    (string/starts-with? path-part ":")
    {:token    :param
     :param-id (-> path-part (subs 1) keyword)}

    :else
    {:token path-part}))

(defn- route->path
  [route]
  (let [{:keys [path]} route
        ;; The :path-terms in the route are insufficient:
        ;; - weird case for a root path ("/")
        ;; - weird case for path below a root path
        path-terms (->> (string/split path #"/+")
                        (drop-while #(= "" %))
                        (mapv path-part->term))
        tokens     (mapv :token path-terms)
        category   (cond
                     (= :wild (last tokens)) :wild          ; has a wild at end, may also have params
                     (some keyword? tokens) :param          ; has params (but no wild)
                     :else :literal)]                       ; just literal string tokens
    {:unmatched-terms path-terms                            ; consumed during the compilation of the matcher
     :route           route
     :category        category}))

(defn- conj-set
  [set v]
  (conj (or set #{}) v))

(defn- unmatched-tokens
  [path]
  (->> path :unmatched-terms (mapv :token)))

(defn- path-conflicts?
  [path other-path]
  ;; With priorities, it's just a case of whether every token matches.  When actual
  ;; matching at runtime, for each term, a literal will be matched before a param, which will
  ;; be matched before a wild.
  ;;
  ;; However, this means that /foo/:bar/baz and /foo/gnip/:gnop also do not conflict.
  ;;
  ;; /foo/gnip/:gnop will have higher priority, because the differentiating literal term
  ;; comes earlier.  Only if /foo/gnip/:gnop fails to match will /foo/:bar/baz be considered.
  ;;
  ;; foo/gnip/baz will match /foo/gnip/:gnop, and not /foo/:bar/baz
  ;;
  ;; Likewise, /foo/bar/*baz and /foo/:bar/:baz do not conflict, because
  ;; /foo:bar/baz will be considered first (param before wild) and only if that fails to match
  ;; will /foo/bar/*baz match.
  (= (unmatched-tokens path)
     (unmatched-tokens other-path)))

(defn- collect-path-conflicts
  [conflicts path other-paths]
  (reduce (fn [conflicts other-path]
            (if (path-conflicts? path other-path)
              (update conflicts (-> path :route :route-name)
                      conj-set (-> other-path :route :route-name))
              conflicts))
          conflicts
          other-paths))

(defn- collect-conflicts
  "Identifies conflicts between the provided paths. Each path is compared against all following paths to see if
  they overlap. When a conflict is identified, the *conflicts volatile map is updated."
  [*conflicts paths]
  (vswap! *conflicts
          (fn [initial-conflicts]
            (loop [conflicts initial-conflicts
                   paths     paths]
              (if-not (seq paths)
                conflicts
                (let [[path & more-paths] paths]
                  (recur (collect-path-conflicts conflicts path more-paths)
                         more-paths)))))))

(defn- literal-suffix-matcher
  "Used when all the path terms are literals (no :param or :wild)."
  [expected-terms route]
  (let [expected-path (when (seq expected-terms)
                        (string/join "/" expected-terms))]
    (fn match-literal-suffix [remaining-path params-map]
      (when (= remaining-path expected-path)
        [route params-map]))))

(defn- literal-prefix-matcher
  "Matches some literal path terms before delegating to another path matcher."
  [expected-terms-prefix next-fn]
  (let [expected-prefix (str (string/join "/" expected-terms-prefix) "/")
        n               (count expected-prefix)]
    (fn match-literal-prefix [remaining-path params-map]
      (when (string/starts-with? remaining-path expected-prefix)
        (next-fn (subs remaining-path n) params-map)))))

(defn- tail-param-matcher
  [param-id route]
  (fn match-tail-param [remaining-path params-map]
    (when remaining-path
      (with-split-path remaining-path [term more-path]
                       (when (nil? more-path)
                         [route (assoc params-map param-id term)])))))

(defn- param-matcher
  [param-id next-fn]
  (fn match-param [remaining-path params-map]
    (when remaining-path
      (with-split-path remaining-path [term more-path]
                       (when more-path
                         (next-fn more-path (assoc params-map param-id term)))))))

(defn- wild-matcher
  ;; Wild is always at the end
  [param-id route]
  (fn match-wild [remaining-path params-map]
    (when (pos? (count remaining-path))
      [route (assoc params-map param-id remaining-path)])))

(defn- prefix-length
  [pred coll]
  (let [n (count coll)]
    (loop [i 0]
      (cond
        (= i n)
        i

        (pred (nth coll i))
        (recur (inc i))

        :else
        i))))

(defn- build-matcher-stack
  "Recursively build the stack of functions that match a vector of
  path strings (from the request :path-info) to a route and map of params."
  [unmatched-terms route]
  (let [unmatched-tokens (mapv :token unmatched-terms)
        n-leading-string (prefix-length string? unmatched-tokens)]
    (cond
      (= (count unmatched-tokens) n-leading-string)
      (literal-suffix-matcher unmatched-tokens route)

      (pos? n-leading-string)
      (literal-prefix-matcher (subvec unmatched-tokens 0 n-leading-string)
                              (build-matcher-stack
                                (subvec unmatched-terms n-leading-string)
                                route))
      :else
      (let [{:keys [token param-id]} (first unmatched-terms)]
        (cond
          (= :wild token)
          (wild-matcher param-id route)

          (= 1 (count unmatched-tokens))
          (tail-param-matcher param-id route)

          :else
          (param-matcher param-id
                         (build-matcher-stack (subvec unmatched-terms 1)
                                              route)))))))


(defn- matcher-from-path
  [_matched path]
  (let [{:keys [unmatched-terms route]} path]
    (build-matcher-stack unmatched-terms route)))

(defn- combine-matchers
  [matched matcher-fns]
  (let [n (count matcher-fns)]
    (case n
      0 (throw (ex-info "Sanity check: no matchers"
                        {:matched matched}))
      1 (first matcher-fns)

      2 (let [[m1 m2] matcher-fns]
          (fn match-one-of-two [remaining-path params-map]
            (or (m1 remaining-path params-map)
                (m2 remaining-path params-map))))

      3 (let [[m1 m2 m3] matcher-fns]
          (fn match-one-of-three [remaining-path params-map]
            (or (m1 remaining-path params-map)
                (m2 remaining-path params-map)
                (m3 remaining-path params-map))))

      ;; Default, general case
      (fn [remaining-path params-map]
        (reduce (fn match-one-of-several [_ matcher]
                  (when-some [result (matcher remaining-path params-map)]
                    (reduced result)))
                nil
                matcher-fns)))))

(defn- drop-first-in-path
  [path]
  (update path :unmatched-terms subvec 1))

(declare subdivide-by-path)

(defn- match-via-lookup
  [paths]
  (let [path->route (reduce (fn [m path]
                              (assoc m
                                     (let [tokens (->> path :unmatched-terms (mapv :token))]
                                       ;; nil is a valid key that matches when all the unmatched tokens in the path
                                       ;; have been consumed by prior matchers.
                                       (when (seq tokens)
                                         (string/join "/" tokens)))
                                     (:route path)))
                            {}
                            paths)]
    (fn match-by-remaining-path [remaining-path path-params]
      (when-let [route (get path->route remaining-path)]
        [route path-params]))))

(defn- matcher-by-first-token
  "Creates a matcher function covering all the cases where the first token is not a parameter.
  The paths may all be literal, or may contain parameters."
  [matched token->paths]
  (let [all-paths     (->> token->paths
                           vals
                           (reduce into []))
        all-literals? (and (seq all-paths)
                           (every? #(= :literal (:category %)) all-paths))]
    (if all-literals?
      (match-via-lookup all-paths)
      (let [literal-term->matcher (reduce
                                    (fn [m [literal-token paths-for-token]]
                                      (let [paths-for-token' (mapv drop-first-in-path paths-for-token)
                                            matched'         (conj matched literal-token)
                                            ;; There may be multiple paths with the same first token (i.e., /foo/bar/baz and /foo/gnip) so
                                            ;; it may be necessary to strip off "/foo" and subdivide again.  But when
                                            ;; there's just one, we can build a single matcher function.
                                            matcher          (if (= 1 (count paths-for-token'))
                                                               (->> paths-for-token' first (matcher-from-path matched'))
                                                               (subdivide-by-path matched' paths-for-token'))]
                                        (assoc m literal-token matcher)))
                                    {}
                                    token->paths)]
        (fn [remaining-path params-map]
          (when remaining-path
            (with-split-path remaining-path [first-term more-path]
                             (when-let [matcher (literal-term->matcher first-term)]
                               (matcher more-path params-map)))))))))

(defn- subdivide-by-path
  [matched paths]
  (let [[completed-paths other-paths] (categorize-by #(-> % :unmatched-terms empty?) paths)
        ;; This is the case where you have a route that is complete, and other routes that
        ;; extend from it: i.e. "/user" and "/user/:id".  The first will be an empty path
        ;; (once "user" is matched) and it is handled here, "/user/:id" will be handled as part
        ;; of by-first-token
        completed-paths-matcher (when (seq completed-paths)
                                  ;; TODO: Should only be one, right? Unless conflicts.
                                  (let [route (-> completed-paths first :route)]
                                    (if (= "/" (:path route))
                                      (fn root-match-completed [remaining-path params-map]
                                        (when (= "" remaining-path)
                                          [route params-map]))
                                      (fn match-completed [remaining-path params-map]
                                        (when (nil? remaining-path)
                                          [route params-map])))))
        by-first-token          (group-by #(-> % :unmatched-terms first :token) other-paths)
        {params :param
         wilds  :wild} by-first-token
        ;; wilds is technically plural but should not ever be more than 1 (unless conflicts exist)
        by-first-literal-token  (dissoc by-first-token :param :wild)
        literal-matcher         (when (seq by-first-literal-token)
                                  (matcher-by-first-token matched by-first-literal-token))
        ;; This is where "priority" comes in ... this order ensures that a literal path matcher
        ;; will match before any path where the first term is a param, and all of those before the (should be zero or one)
        ;; that are wild.
        all-matchers            (cond-> []
                                  completed-paths-matcher (conj completed-paths-matcher)
                                  literal-matcher (conj literal-matcher)
                                  params (into (mapv #(matcher-from-path matched %) params))
                                  wilds (into (mapv #(matcher-from-path matched %) wilds)))]
    (combine-matchers matched all-matchers)))

(defn- match-by-path
  [*conflicts matched routes]
  (let [paths   (mapv route->path routes)
        matcher (subdivide-by-path matched paths)]
    (collect-conflicts *conflicts paths)                    ; Side effect
    (fn match-on-path [{:keys [path-info]}]
      ;; Strip off the leading slash and start matching
      (matcher (subs path-info 1) nil))))

(defn- subdivide-by-request-key
  [filters matched routes *conflicts]
  ;; matched here is a map, which becomes the first element in a vector once
  ;; we start matching by path (the other elements are terms from the path).
  ;; This isn't actually needed at all for the logic, but it's very handy
  ;; for debugging. It "costs" very little, and that cost is only during the
  ;; construction of the routing function, with no cost during execution of that
  ;; function.
  (if-not (seq filters)
    (match-by-path *conflicts [matched] routes)
    (let [[first-filter & more-filters] filters
          [request-key route-key match-any-value] first-filter
          grouped           (group-by route-key routes)
          grouped'          (dissoc grouped match-any-value)
          match-any-routes  (get grouped match-any-value [])
          ;; match-any-matcher is what matches when the value from the request does not match
          ;; any value for any route.
          match-any-matcher (if (seq match-any-routes)
                              (subdivide-by-request-key more-filters
                                                        (assoc matched route-key match-any-value)
                                                        match-any-routes
                                                        *conflicts)
                              return-nil)]
      ;; So, if none of the routes care about this particular request key, then we can optimize:
      ;; we can skip right to the match-any-matcher as if we looked it up in the dispatch-map
      ;; and did not find a match.
      (if-not (seq grouped')
        match-any-matcher
        (let [dispatch-map (reduce-kv
                             (fn [m match-value routes-for-value]
                               (let [all-routes (into match-any-routes routes-for-value)
                                     matcher    (subdivide-by-request-key
                                                  more-filters
                                                  (assoc matched route-key match-value)
                                                  all-routes
                                                  *conflicts)]
                                 (assoc m match-value matcher)))
                             {}
                             grouped')]
          (case (count dispatch-map)

            1
            (let [[solo-value
                   solo-matcher] (first dispatch-map)]
              (fn match-request-key-solo [request]
                (let [matcher (if (= solo-value (request-key request)) solo-matcher match-any-matcher)]
                  (matcher request))))

            (fn match-request-key [request]
              (let [matcher (get dispatch-map (request-key request) match-any-matcher)]
                (matcher request)))))))))

(defn create-matcher-from-routes
  "Given a routing table, constructs a function that can be passed a request map,
  and returns a tuple of [route params-map] or nil if no match.

  This function returns a tuple of [matcher-fn conflicts]."
  [routes]
  (let [*conflicts (volatile! nil)
        matcher-fn (subdivide-by-request-key
                     ;; Could be that some analysis of the routes would identify an optimum order
                     ;; for these.
                     [[:server-port :port nil]
                      [:server-name :host nil]
                      [:scheme :scheme nil]
                      [:request-method :method :any]]
                     {}
                     routes
                     *conflicts)]
    [matcher-fn @*conflicts]))


(defn- format-route
  [{:keys [route-name method path]}]
  (list [:bold route-name]
        " ("
        (if (= :any method)
          [:italic "ANY"]
          (-> method name string/upper-case))
        " " path ")"))

(defn report-conflicts
  [conflicts routes]
  (when-not *squash-conflicts-report*
    (perr [:bold.yellow "Conflicting routes were identified:"])
    ;; May need to do some work if route's reflect each other (A conflicts with B, B conflicts with A).
    ;; Haven't seen a way to provoke that, yet, so hopefully not a problem.
    (let [name->route (reduce (fn [m route]
                                (assoc m (:route-name route) route))
                              {}
                              routes)]
      (doseq [route-name (-> conflicts keys sort)
              :let [route    (name->route route-name)
                    others   (->> (conflicts route-name)
                                  (map name->route)
                                  (sort-by :route-name))
                    n-others (count others)]]
        (perr [:yellow
               (format-route route)
               " conflicts with "
               (if (= 1 n-others)
                 "route"
                 (list n-others " routes"))
               ":"])
        (doseq [other others]
          (perr [:yellow " - " (format-route other)]))))))
