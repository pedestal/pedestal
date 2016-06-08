; Copyright 2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.content-negotiation
  (:require [clojure.string :as string]
            [io.pedestal.interceptor :as interceptor])
  (:import (java.util List)))

;; Parsing the headers, building the map
;; --------------------------------------
;;   These functions are all public to allow others to build
;;   custom accept-* handling (language, charset, etc.)
(defn parse-accept-element [^String accept-elem-str]
  (let [[field & param-strs] (string/split accept-elem-str #";")
        field (string/trim field)
        [t st] (string/split field #"/")
        params (into {:q "1.0"}
                     (map (fn [s]
                            (let [[k v] (string/split s #"=")]
                              [(-> k string/trim string/lower-case keyword)
                               (string/trim v)])) param-strs))]
    {:field field
     :type t
     :subtype st
     :params (update-in params [:q] #(Double/parseDouble %))}))

(defn parse-accept-* [^String accept-str]
  (let [accept-elems (string/split accept-str #",")
        elem-maps (mapv parse-accept-element accept-elems)]
    elem-maps))

(defn weighted-accept-qs [supported-types accept-elem]
  (when-let [weighted-qs (seq
                             (for [target supported-types
                                   :let [weighted-q (if (and (= (:type accept-elem) (:type target))
                                                             (or (= (:subtype accept-elem)
                                                                    (:subtype target))
                                                                 (= (:subtype accept-elem) "*")))
                                                      (+ (get-in accept-elem [:params :q])
                                                       (if (= (:type accept-elem)
                                                              (:type target))
                                                         100 0)
                                                       (if (= (:type accept-elem) "*")
                                                         50 0)
                                                       (if (= (:subtype accept-elem)
                                                              (:subtype target))
                                                         10 0)
                                                       (if (= (:subtype accept-elem) "*")
                                                         5 0)
                                                       (reduce (fn [acc [k v]]
                                                                 (if (= (get-in target [:params k]) v)
                                                                   (inc acc)
                                                                   acc))
                                                               0
                                                               (:params accept-elem)))
                                                      0)]
                                   :when (> weighted-q 50)]
                               [weighted-q target accept-elem]))]
      ;; `max-key` doesn't have knowledge about the `supported-types` preference order.
      ;;  -- we'll do the `reduce` manually
      (reduce (fn [[max-q max-t _ :as max-vec] [weighted-q new-t _ :as new-q-vec]]
                (cond
                  (> weighted-q max-q) new-q-vec
                  (= weighted-q max-q) (if (< (.indexOf ^List supported-types new-t)
                                              (.indexOf ^List supported-types max-t))
                                         new-q-vec max-vec)
                  :else max-vec))
              [0 nil nil]
              weighted-qs)))

(defn best-match-fn [supported-type-strs]
  {:pre [(not-empty supported-type-strs)
         (if (coll? supported-type-strs)
           (every? not-empty supported-type-strs)
           true)]}
  (let [supported-type-strs (if (coll? supported-type-strs)
                          supported-type-strs [supported-type-strs])
        supported-types (map parse-accept-element supported-type-strs)
        weight-fn #(weighted-accept-qs supported-types %)]
    (fn [parsed-accept-maps]
      (persistent!
        (reduce (fn [acc accept-map]
                  (let [[weight t am] (weight-fn accept-map)]
                    (if (and weight (> weight (:max-weight acc)))
                      (assoc! acc
                              :max-weight weight
                              :type (dissoc t :params)
                              :accept-requested am)
                      acc)))
                (transient {:max-weight 0})
                parsed-accept-maps)))))

(defn best-match
  [match-fn parsed-accept-maps]
  (:type (match-fn parsed-accept-maps)))


(comment
  (def example-accept (string/join " , "
                                   ["*/*;q=0.2"
                                    "foo/*;    q=0.2"
                                    "spam/*; q=0.5"
                                    "foo/baz; q    =   0.8"
                                    "foo/bar"
                                    "foo/bar;baz=spam"]))

  (best-match
    (best-match-fn
      ;["foo/bar;baz=spam" "foo/bar"]
      ;["foo/bar"]
      ;["spam/bar"]
      ;["foo/burt"]
      ;["no/match"]
      ;["foo/burt" "spam/burt" "no/match"]
      ["foo/burt" "spam/burt" "foo/bar" "no/match"]
      )
    (parse-accept-* example-accept)) ; => foo/bar

  (best-match
    (best-match-fn ["foo/burt" "spam/burt" "foo/bar"])
    (parse-accept-* "no/match")) ; => nil

  (best-match
    (best-match-fn ["foo/burt" "spam/burt" "foo/bar"])
    (parse-accept-* "qux/*")) ; => nil

  (best-match
    (best-match-fn ["foo/burt" "spam/burt" "foo/bar"])
    (parse-accept-* "foo/bonk")) ; => nil

  ;; Factor in the preference, listed in the order of supported-types
  (best-match
    (best-match-fn ["foo/burt" "spam/burt" "foo/bar"])
    (parse-accept-* "foo/*")) ; => foo/burt
)

;; Interceptor
;; -----------
(defn negotiate-content
  "Given a vector of strings (supported types mime-types) and
  optionally a map of additional options,
  return an interceptor that will parse client-request response formats,
  and add an `:accept` key on the request, of the most acceptable response format.

  The format of the `:accept` value is a map containing :field, :type, and :subtype - all strings

  Additional options:
   :no-match-fn - A function that takes a context; Called when no acceptable format/mime-type is found
   :content-param-paths - a vector of vectors; paths into the context to find 'accept' format strings"
  ([supported-type-strs]
   (negotiate-content supported-type-strs {}))
  ([supported-type-strs opts-map]
  (assert (not-empty supported-type-strs)
          (str "Content-negotiation interceptor requires content-types; Cannot be empty. Instead found: " (pr-str supported-type-strs)))
  (assert (if (coll? supported-type-strs) (every? not-empty supported-type-strs) true)
          (str "All content-negotiated types must be valid. Found an empty string: " (pr-str supported-type-strs)))
  (assert (if (coll? supported-type-strs) (every? string? supported-type-strs) true)
          (str "All content-negotiated types must be strings.  Found: " (pr-str supported-type-strs)))
  (let [match-fn (best-match-fn supported-type-strs)
        {:keys [no-match-fn content-param-paths]
         :or {no-match-fn (fn [ctx]
                            (assoc ctx :response {:status 406
                                                  :body "Not Acceptable"}))
              content-param-paths [[:request :headers "accept"]
                                   [:request :headers :accept]]}} opts-map]
    (interceptor/interceptor
      {:name ::negotiate-content
       :enter (fn [ctx]
                (if-let [accept-param (loop [[path & paths] content-param-paths]
                                        (if-let [a-param (get-in ctx path)]
                                          a-param
                                          (if (empty? paths)
                                            nil
                                            (recur paths))))]
                  (if-let [content-match (best-match match-fn (parse-accept-* accept-param))]
                    (assoc-in ctx [:request :accept] content-match)
                    (no-match-fn ctx))
                  ctx))}))))

