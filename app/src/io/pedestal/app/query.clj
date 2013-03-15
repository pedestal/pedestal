; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app.query
    "A datalog-like query engine which can be used on both the client
    and the server."
    (:require [clojure.set :as set]))

(defprotocol TupleSource
  (tuple-seq [this]
    "Generate a sequence of tuples."))

(defn logic-variable? [x]
  (and (symbol? x)
       (= (first (name x)) \?)))

(defn datasource? [x]
  (and (symbol? x)
       (= (first (name x)) \$)))

(defn unifier [bindings clause fact]
  (reduce (fn [a [c t]]
            (let [c (if (and (logic-variable? c) (contains? bindings c))
                      (get bindings c)
                      c)]
              (when a
                (cond (logic-variable? c) (assoc a c t)
                      (= c t) a
                      :else nil))))
          {}
          (partition 2 (interleave clause fact))))

(defn unifiers-for-clause [bindings clause facts]
  (keep (partial unifier bindings clause) facts))

(defn unifiers [bindings clauses facts]
  (reduce (fn [a x]
            (conj a (unifiers-for-clause bindings x facts)))
          []
          clauses))

(defn combine-unifiers [head tail]
  (let [ks (set/intersection (set (keys (first head))) (set (keys (first tail))))]
    (if (empty? ks)
      tail
      (for [x head y tail :when (= (select-keys x ks) (select-keys y ks))]
        (merge x y)))))

(defn fold [unifiers]
  (cond (some empty? unifiers) [[]]
        (< (count unifiers) 1) [[]]
        (= (count unifiers) 1) unifiers
        :else
        (let [head (first unifiers)
              tail (rest unifiers)]
          (recur (reduce (fn [a b]
                           (conj a (combine-unifiers head b)))
                         []
                         tail)))))

(defn ->tuples [data]
  (cond (vector? data) data
        (satisfies? TupleSource data) (tuple-seq data)
        :else []))

(defn produce [bindings clauses facts]
  (let [unifiers (reduce (fn [a k]
                           (concat a (unifiers bindings
                                               (get clauses k)
                                               (->tuples (get facts k)))))
                         []
                         (keys clauses))]
    (first (fold unifiers))))

(defn parse-query [query]
  (reduce (fn [a x]
            (cond (contains? #{:find :in :where} x) (assoc a :on x)
                  (= (:on a) :find) (update-in a [:find] conj x)
                  (= (:on a) :in)
                  (if (= x '$)
                    a
                    (update-in a [:in] conj x))
                  (= (:on a) :where)
                  (if (datasource? (first x))
                    (update-in a [:clauses (first x)] (fnil conj []) (vec (rest x)))
                    (update-in a [:clauses '$] (fnil conj []) x)) 
                  :else a))
          {:find []
           :in ['$]
           :clauses {'$ []}}
          query))

(defn q [query & sources]
  (let [{:keys [clauses find in]} (parse-query query)]
    (assert (= (count sources) (count in))
            "Datasource count does not match named input count.")
    (let [source-map (zipmap in sources)
          parameters (remove datasource? (keys source-map))
          data-sources (filter datasource? (keys source-map))
          results (produce (select-keys source-map parameters) clauses source-map)]
      (reduce (fn [a b]
                (conj a (vec (map #(get b %) find))))
              []
              results))))

