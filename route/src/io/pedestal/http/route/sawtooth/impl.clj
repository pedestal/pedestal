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
  (:require [clojure.string :as string]))

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

(defn- conj-nil
  [set v]
  (conj (or set #{}) v))

(defn- tokens-match?
  [path-tokens other-tokens]
  (and (= (count path-tokens)
          (count other-tokens))
       (->> (map vector path-tokens other-tokens)
            (every? (fn [[p o]]
                      (or (keyword? p)
                          (keyword? o)
                          (= p o)))))))

(defn- wild-tokens-match?
  [path-tokens other-tokens]
  (loop [[l-token & more-l-tokens] path-tokens
         [r-token & more-r-tokens] other-tokens]
    (cond
      ;; They can't both exhaust at the same time because at least one
      ;; ends with a :wild.
      (or (nil? l-token)
          (nil? r-token))
      false

      (or (= :wild l-token)
          (= :wild r-token))
      true

      (or (keyword? l-token)
          (keyword? r-token)
          (= l-token r-token))
      (recur more-l-tokens more-r-tokens)

      ;; Found a mismatched literal term
      :else
      false)))

(defn- path-conflicts?
  [path other-path]
  (let [path-tokens    (->> path :unmatched-terms (mapv :token))
        path-category  (:category path)
        other-tokens   (->> other-path :unmatched-terms (mapv :token))
        other-category (:category other-path)]
    (if (or (= :wild path-category)
            (= :wild other-category))
      (wild-tokens-match? path-tokens other-tokens)
      (tokens-match? path-tokens other-tokens))))

(defn- collect-path-conflicts
  [conflicts path other-paths]
  (reduce (fn [conflicts other-path]
            (if (path-conflicts? path other-path)
              (update conflicts (-> path :route :route-name)
                      conj-nil (-> other-path :route :route-name))
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
  [_matched path]
  (let [{:keys [unmatched-terms route]} path
        has-wild?    (-> unmatched-terms last :token (= :wild))
        path-matcher (build-matcher-stack unmatched-terms
                                          route)
        n            (count unmatched-terms)]
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
  (update path :unmatched-terms subvec 1))

(defn- subdivide-by-path
  [matched paths]
  (let [[empty-paths non-empty-paths] (categorize-by #(-> % :unmatched-terms empty?) paths)
        ;; This is the case where you have a route that is complete, and other routes that
        ;; extend from it: i.e. "/user" and "/user/:id".  The first will be an empty path
        ;; (once "user" is matched) and it is handled here, "/user/:id" will be handled as part
        ;; of by-first-token
        empty-paths-matcher   (when (seq empty-paths)
                                (let [route          (-> empty-paths first :route)
                                      match-terminal (fn [_ params-map]
                                                       [route params-map])]
                                  (guard-exact-length 0 match-terminal)))
        by-first-token        (group-by #(-> % :unmatched-terms first :token) non-empty-paths)
        {params :param
         wilds  :wild} by-first-token
        ;; wilds is plural *but* should not ever be more than 1
        by-first-token'       (dissoc by-first-token :param :wild)
        literal-term->matcher (reduce
                                (fn [m [literal-token paths-for-token]]
                                  (let [paths-for-token' (mapv drop-first-in-path paths-for-token)
                                        matched'         (conj matched literal-token)
                                        matcher          (if (= 1 (count paths-for-token'))
                                                           (->> paths-for-token' first (matcher-from-path matched'))
                                                           (subdivide-by-path matched' paths-for-token'))]
                                    (assoc m literal-token matcher)))
                                {}
                                by-first-token')
        literal-matcher       (when (seq literal-term->matcher)
                                (fn [remaining-path-terms params-map]
                                  (let [first-term (first remaining-path-terms)
                                        matcher    (literal-term->matcher first-term)]
                                    (when matcher
                                      (matcher (subvec remaining-path-terms 1) params-map)))))
        all-matchers          (cond-> []
                                empty-paths-matcher (conj empty-paths-matcher)
                                literal-matcher (conj literal-matcher)
                                params (into (mapv #(matcher-from-path matched %) params))
                                wilds (into (mapv #(matcher-from-path matched %) wilds)))]
    (combine-matchers matched all-matchers)))

(defn- match-by-path
  [*conflicts matched routes]
  (let [paths             (mapv route->path routes)
        [empty-paths non-empty-paths] (categorize-by #(-> % :unmatched-terms empty?) paths)
        empty-matcher     (when-some [empty-route (-> empty-paths first :route)]
                            (guard-exact-length 0
                                                (fn [_ path-params]
                                                  [empty-route path-params])))
        non-empty-matcher (subdivide-by-path matched non-empty-paths)
        matcher           (if empty-matcher
                            (combine-matchers matched [empty-matcher non-empty-matcher])
                            non-empty-matcher)]
    (collect-conflicts *conflicts paths)                    ; Side effect
    (fn match-on-path [{:keys [path-info]}]
      (let [path-terms (->> (string/split path-info #"/")
                            (drop 1)                        ; leading slash
                            vec)]
        (matcher path-terms nil)))))

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
                              (fn [_request] nil))]
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
          (fn match-request-key [request]
            (let [matcher (get dispatch-map (request-key request) match-any-matcher)]
              (matcher request))))))))

(defn create-matcher-from-routes
  "Given a routing table, returns a function that can be passed a request map,
  and returns a tuple of [route params-map] or nil if no match.

  This function returns a tuple of [matcher-fn conflicts]."
  [routes]
  (let [*conflicts (volatile! nil)
        matcher-fn (subdivide-by-request-key
                     [[:server-port :port nil]
                      [:server-name :host nil]
                      [:scheme :scheme nil]
                      [:request-method :method :any]]
                     {}
                     routes
                     *conflicts)]
    [matcher-fn @*conflicts]))

