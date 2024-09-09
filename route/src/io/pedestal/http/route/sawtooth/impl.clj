; Copyright 2024 Nubank NA
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
            [net.lewisship.trace :refer [trace]]))

(defn- simplify-route
  [route]
  (select-keys route [:path :method :port :host :scheme]))

(defn- simplify-path
  [path]
  (update path :route simplify-route))

(defn- divide-by
  [pred coll]
  (let [{true-values  true
         false-values false} (group-by pred coll)]
    [true-values false-values]))

;; Routes are wrapped as Paths
;; we then recursively subdivide Paths by prefix
;; once we've reduced the Paths for a given prefix to a minimum (usually, because of :param or :wild tokens),
;; we can produce a PathMatcher function:
;;  (fn [remaining-path-terms param-map] -> [route param-map'] (or nil)
;; remaining-path-terms: a vector of strings, derived from the request-map :uri
;; As terms are matched, they are removed from the front using subvec
;; param-map is a map from keyword param id to a string value (for :param and :wild)

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
        ;; - hard to tell if a param is for a single term or wild
        ;; So we redo that work here, and beef it up a little (redundant slashes
        ;; are treated as one).
        path-terms (->> (string/split path #"/+")
                        (drop-while #(= "" %))
                        (mapv path-part->term))]
    {:unmatched-terms path-terms
     :route           route}))

(comment
  (route->path {:path "///user/:id/contents/*path"})
  )

(defn- literal-suffix-matcher
  "Used when all the path terms are literals (no :param or :wild)."
  [expected-terms route]
  (fn match-literal-suffix [remaining-path-terms params-map]
    (when (= remaining-path-terms expected-terms)
      [route params-map])))

(defn- literal-prefix-matcher
  "Matches some literal path terms before delegating to another path matcher."
  [expected-terms-prefix next-fn]
  (let [n (count expected-terms-prefix)]
    (fn match-literal-prefix [remaining-path-terms params-map]
      (when (= expected-terms-prefix (subvec remaining-path-terms 0 n))
        (next-fn (subvec remaining-path-terms n) params-map)))))

(defn- tail-param-matcher
  [param-id route]
  (fn match-tail-param [remaining-path-terms params-map]
    ;; Assumes a wrapping function checked the length, so that there's only exactly one
    ;; term left.
    [route (assoc params-map param-id (first remaining-path-terms))]))

(defn- param-matcher
  [param-id next-fn]
  (fn match-param [remaining-path-terms params-map]
    (let [term (first remaining-path-terms)]
      (next-fn (subvec remaining-path-terms 1)
               (assoc params-map param-id term)))))

(defn- wild-matcher
  ;; Wild is always at the end
  [param-id route]
  (fn match-wild [remaining-path-terms params-map]
    [route (assoc params-map param-id (string/join "/" remaining-path-terms))]))

;; guards are matchers that check for a particular state
;; before delegating to another matcher.

(defn- guard-exact-length
  [expected-length next-fn]
  (fn exact-length-guard [remaining-path-terms params-map]
    (when (= expected-length (count remaining-path-terms))
      (next-fn remaining-path-terms params-map))))

(defn- guard-min-length
  [min-length next-fn]
  (fn min-length-guard [remaining-path-terms params-map]
    (when (<= min-length (count remaining-path-terms))
      (next-fn remaining-path-terms params-map))))

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
  [matched path]
  (trace :matched matched
         :path (simplify-path path))
  (let [{:keys [unmatched-terms route]} path
        has-wild? (-> unmatched-terms last :token (= :wild))
        path-matcher (build-matcher-stack unmatched-terms
                                          route)
        n (count unmatched-terms)]
    (if has-wild?
      ;; The wild has to match at least one path term
      (guard-min-length n path-matcher)
      ;; Must match exactly as many terms (no more or less)
      (guard-exact-length n path-matcher))))

(defn- combine-matchers
  [matched matcher-fns]
  (let [n (count matcher-fns)]
    (case n
      0 (throw (ex-info "Sanity check: no matchers"
                        {:matched matched}))
      1 (first matcher-fns)

      ;; Maybe do 3, 4, 5 as well?  Or is even this overkill?
      2 (let [[m1 m2] matcher-fns]
          (fn match-one-of-two [remaining-path-terms params-map]
            (or (m1 remaining-path-terms params-map)
                (m2 remaining-path-terms params-map))))

      ;; Default, general case
      (fn [remaining-path-terms params-map]
        (reduce (fn match-one-of-several [_ matcher]
                  (when-some [result (matcher remaining-path-terms params-map)]
                    (reduced result)))
                nil
                matcher-fns)))))

(defn- drop-first-in-path
  [path]
  (try
    (update path :unmatched-terms subvec 1)
    (catch Throwable t
      (trace :path path)
      (throw t))))

(defn- subdivide-by-path
  [matched paths]
  (trace :matched matched
         :paths (mapv simplify-path paths))
  (let [[empty-paths non-empty-paths] (divide-by #(-> % :unmatched-terms empty?) paths)
        ;; This is the case where you have a route that is complete, and other routes that
        ;; extend from it: i.e. "/user" and "/user/:id".  The first will be an empty path
        ;; (once "user" is matched) and it is handled here, "/user/:id" will be handled as part
        ;; of by-first-token
        empty-paths-matcher (when (seq empty-paths)
                              ;; TODO: Check that there's exactly one empty path
                              (let [route (-> empty-paths first :route)
                                    match-terminal (fn [_ params-map]
                                                     [route params-map])]
                                (guard-exact-length 0 match-terminal)))
        by-first-token (group-by #(-> % :unmatched-terms first :token) non-empty-paths)
        {params :param
         wilds  :wild} by-first-token
        ;; wilds is plural *but* should not ever be more than 1
        by-first-token' (dissoc by-first-token :param :wild)
        ;; TODO: This is where we can do some checks for when
        ;; params and wilds conflict with others.
        literal-term->matcher (reduce
                                (fn [m [literal-token paths-for-token]]
                                  (let [paths-for-token' (mapv drop-first-in-path paths-for-token)
                                        matched' (conj matched literal-token)
                                        matcher (if (= 1 (count paths-for-token'))
                                                  (->> paths-for-token' first (matcher-from-path matched'))
                                                  (subdivide-by-path matched' paths-for-token'))]
                                    (assoc m literal-token matcher)))
                                {}
                                by-first-token')
        literal-matcher (when (seq literal-term->matcher)
                          (fn [remaining-path-terms params-map]
                            (let [first-term (first remaining-path-terms)
                                  matcher (literal-term->matcher first-term)]
                              (when matcher
                                (matcher (subvec remaining-path-terms 1) params-map)))))
        all-matchers (cond-> []
                             empty-paths-matcher (conj empty-paths-matcher)
                             literal-matcher (conj literal-matcher)
                             params (into (mapv #(matcher-from-path matched %) params))
                             wilds (into (mapv #(matcher-from-path matched %) wilds)))]
    (combine-matchers matched all-matchers)))

(defn- match-by-path
  [matched routes]
  (trace :matched matched
         :routes (mapv simplify-route routes))
  (let [paths (mapv route->path routes)
        [empty-paths non-empty-paths] (divide-by #(-> % :unmatched-terms empty?) paths)
        empty-matcher (when-some [empty-route (-> empty-paths first :route)]
                        ;; TODO: Check for conflicts!
                        (guard-exact-length 0
                                            (fn [_ path-params]
                                              [empty-route path-params])))
        non-empty-matcher (subdivide-by-path matched non-empty-paths)
        matcher (if empty-matcher
                  (combine-matchers matched [empty-matcher non-empty-matcher])
                  non-empty-matcher)]
    (fn match-on-path [{:keys [path-info]}]
      (let [path-terms (->> (string/split path-info #"/")
                            (drop 1)                        ; leading slash
                            vec)]
        (matcher path-terms nil)))))

(defn- subdivide-by-request-key
  [filters matched routes]
  (if-not (seq filters)
    (match-by-path matched routes)
    (let [[first-filter & more-filters] filters
          [request-key route-key match-any-value] first-filter
          grouped (group-by route-key routes)
          grouped' (dissoc grouped match-any-value)
          match-any-routes (get grouped match-any-value [])
          ;; match-any-matcher is what matches when the value from the request does not match
          ;; any value for any route.
          match-any-matcher (if (seq match-any-routes)
                              (subdivide-by-request-key more-filters
                                                        (conj matched [route-key match-any-value])
                                                        match-any-routes)
                              (fn [_request] nil))]
      ;; So, if none of the routes care about this particular request key, then we can optimize:
      ;; we can skip right to the match-any-matcher as if we looked it up in the dispatch-map
      ;; and did not find a match.
      (if-not (seq grouped')
        match-any-matcher
        (let [dispatch-map (reduce-kv
                             (fn [m match-value routes-for-value]
                               (let [all-routes (into match-any-routes routes-for-value)
                                     matcher (subdivide-by-request-key
                                               more-filters
                                               (conj matched [route-key match-value])
                                               all-routes)]
                                 (assoc m match-value matcher)))
                             {}
                             grouped')]
          (fn match-request-key [request]
            (let [matcher (get dispatch-map (request-key request) match-any-matcher)]
              (matcher request))))))))

(defn create-matcher-from-routes
  "Given a routing table, returns a function that can be passed a request map,
  and returns a tuple of [route params-map] or nil if no match."
  [routes]
  (subdivide-by-request-key
    [[:server-port :port nil]
     [:server-name :host nil]
     [:scheme :scheme nil]
     [:request-method :method :any]]
    []
    routes))


