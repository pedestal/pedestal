; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app.perf.model.diff)

(declare diff-map)

(defn- diff [prefix o n]
  (cond (identical? o n) nil

        (= o n) nil

        (map? n) (diff-map prefix o n)

        (nil? o) [[prefix :added]]

        (and (map? o) (nil? n)) nil

        (nil? n) [[prefix :removed]]

        :else [[prefix :updated]]))

(defn- diff-map [prefix o n]
  (reduce-kv (fn [a k new-value]
               (if-let [d (diff (conj prefix k) (get o k) new-value)]
                 (into a d)
                 a))
             []
             n))

(defn- reverse-diffs [ds]
  (keep (fn [[p e]] (when (= e :added) [p :removed])) ds))

(defn model-diff-inform
  "Given paths which are known to have changed and an old and new
  model return an inform message describing all changes to the model."
  ([o n]
     (model-diff-inform (mapv vector (keys n)) o n))
  ([paths o n]
     (vec
      (map (fn [[p e]] [p e o n])
           (mapcat
            (fn [p] (let [ov (get-in o p)
                         nv (get-in n p)]
                     (into (diff p ov nv)
                           (reverse-diffs (diff p nv ov)))))
            paths)))))

(defn- matching-path-element?
  "Return true if the two elements match."
  [a b]
  (or (= a b) (= a :*) (= b :*)))

(defn matching-path?
  "Return true if the two paths match."
  [path-a path-b]
  (and (= (count path-a) (count path-b))
       (every? true? (map (fn [a b] (matching-path-element? a b)) path-a path-b))))

(defn- truncate-path [path pattern]
  (vec (take (count pattern) path)))

(defn- generalize-event-entry [[p e o n :as event-entry] pattern]
  (if (> (count p) (count pattern))
    (let [path (truncate-path p pattern)
          event (if (get-in o path) :updated :added)]
      [(truncate-path p pattern) event o n])
    event-entry))

(defn combine [inform-message patterns]
  (reduce (fn [acc [path event o n :as event-entry]]
            (if-let [p (first (filter #(matching-path? % (truncate-path path %))
                                      patterns))]
              (conj acc (generalize-event-entry event-entry p))
              (conj acc event-entry)))
          #{}
          inform-message))
