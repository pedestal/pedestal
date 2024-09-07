(ns io.pedestal.http.route.sawtooth
  (:require [clojure.string :as string]))

;; Routes are wrapped as Paths
;; we then recursively subdivide Paths by prefix
;; once we've reduced the Paths for a given prefix to a minimum (usually, because of :param or :wild tokens),
;; we can produce a PathMatcher function:
;;  (fn [remaining-path-terms param-map] -> [route param-map'] (or nil)
;; remaining-path-terms: a vector of strings, derived from the request-map :uri
;; As terms are matched, they are removed from the front using subvec
;; param-map is a map from keyword param id to a string value (for :param and :wild)


(defn- literal-matcher
  "Used when all the path terms are literals (no :param or :wild)."
  [expected-terms route]
  (fn match-literals [remaining-path-terms params-map]
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

;; Path
;; {:unmatched-tokens ["api" "repo" :param :param "scan"]
;;    :unmatched-terms [{:token "api" :type :string} ...]
;;   :route ...}

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

(comment
  (prefix-length string? [])                                ; 0
  (prefix-length string? ["foo"])                           ; 1
  (prefix-length string? ["foo" :bar])                      ; 1
  (prefix-length string? ["foo" "bar" :baz])                ; 2
  (prefix-length string? [:baz])                            ; 0

  )

(defn- build-matcher-stack
  "Recursively build the stack of functions that match a vector of
  path strings (from the request :uri) to a route and map of params."
  [unmatched-tokens unmatched-terms route]
  (let [n-leading-string (prefix-length string? unmatched-tokens)]
    (cond
      (= (count unmatched-tokens) n-leading-string)
      (literal-matcher unmatched-tokens route)

      (pos? n-leading-string)
      (literal-prefix-matcher (subvec unmatched-tokens 0 n-leading-string)
                              (build-matcher-stack
                                (subvec unmatched-tokens n-leading-string)
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
                         (build-matcher-stack (subvec unmatched-tokens 1)
                                              (subvec unmatched-terms 1)
                                              route)))))))

(defn- matcher-from-path
  [path]
  (let [{:keys [unmatched-tokens unmatched-terms route]} path
        has-wild? (= :wild (last unmatched-terms))
        path-matcher (build-matcher-stack unmatched-tokens
                                          unmatched-terms
                                          route)
        n (count unmatched-tokens)]
    (if has-wild?
      ;; The wild has to match at least one path term
      (guard-min-length n path-matcher)
      ;; Must match exactly as many terms (no more or less)
      (guard-exact-length n path-matcher))))

(defn- combine-matchers
  [matchers]
  (let [n (count matchers)]
    (case n
      0 (throw (IllegalStateException.))
      1 (first matchers)

      ;; Maybe do 3, 4, 5 as well?  Or is even this overkill?
      2 (let [[m1 m2] matchers]
          (fn match-one-of-two [remaining-path-terms params-map]
            (or (m1 remaining-path-terms params-map)
                (m2 remaining-path-terms params-map))))

      ;; Default, general case
      (fn [remaining-path-terms params-map]
        (reduce (fn match-one-of-several [_ matcher]
                  (when-some [result (matcher remaining-path-terms params-map)]
                    (reduced result)))
                nil
                matchers)))))

(defn- path-part->term
  [route path-part path-constraints]
  (cond
    (string? path-part)
    {:token path-part}

    (= (get path-constraints path-part) "(.*)")
    {:token    :wild
     :param-id path-part}


    (= (get path-constraints path-part) "([^/]+)")
    {:token    :param
     :param-id path-part}

    :else
    (throw (ex-info "Path constraints not supported by sawtooth router"
                    {:route      route
                     :constraint path-part}))))

(defn- route->path
  [route]
  (let [{:keys [path-parts path-constraints]} route
        path-terms (mapv #(path-part->term route % path-constraints) path-parts)]
    {:unmatched-tokens (mapv :token path-terms)
     :unmatched-terms  path-terms
     :route            route}))

(defn- nth-token
  [wrapped-route i]
  (-> wrapped-route :tokens (nth i)))

(defn- drop-first-in-path [path]
  (-> path
      (update :unmatched-tokens subvec 1)
      (update :unmatched-terms subvec 1)))

(defn- subdivide-by-terms
  [paths]
  (let [by-first-token #trace/result (group-by #(-> % :unmatched-tokens first) paths)
        {params :param
         wilds  :wild} by-first-token
        ;; wilds is plural *but* should not ever be more than 1
        by-first-token' (dissoc by-first-token :param :wild)
        ;; TODO: This is where we can do some checks for when
        ;; params and wilds conflict with others.
        ;; TODO: Is there a special case for by-first-token' w/ count == 1 ?
        token->matcher (reduce
                         (fn [m [first-token paths-for-token]]
                           (let [paths-for-token' (mapv drop-first-in-path paths-for-token)
                                 matcher (if (= 1 (count paths-for-token'))
                                           ;; TODO: Maybe need to drop-first-in-path on this?
                                           (-> paths-for-token' first matcher-from-path)
                                           (subdivide-by-terms paths-for-token'))]
                             (assoc m first-token matcher)))
                         {}
                         by-first-token')
        literal-matcher (fn [remaining-path-terms params-map]
                          (let [first-token (first remaining-path-terms)
                                matcher (token->matcher first-token)]
                            (when matcher
                              (matcher (subvec remaining-path-terms 1) params-map))))
        all-matchers (cond-> [literal-matcher]
                             params (into (mapv matcher-from-path params))
                             wilds (into (mapv matcher-from-path wilds)))]
    (combine-matchers all-matchers)))

(def x-matcher
  (->> [{:path-parts       ["api" "repos" :id]
         :path-constraints {:id "([^/]+)"}
         :route-name       :get-repo}
        {:path-parts       ["api" "repos" :id "content" :path]
         :path-constraints {:id   "([^/]+)"
                            :path "(.*)"}
         :route-name       :get-repo-content}
        {:path-parts ["api" "stats"]
         :route-name :stats}]
       (map route->path)
       subdivide-by-terms))

(defn x [path]
  (x-matcher (->> (string/split path #"/")
                  (drop 1)
                  vec)
             nil))

(comment
  (x "/api/repos/foo/content/bar")
  )
