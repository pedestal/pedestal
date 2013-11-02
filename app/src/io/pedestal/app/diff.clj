; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app.diff)

(declare diff-map)

(defn- diff [prefix o n]
  (cond (= o n) []

        (map? n)
        (diff-map prefix o n)

        (nil? o)
        [[prefix :added]]

        (nil? n)
        [[prefix :removed]]

        :else [[prefix :updated]]))

(defn- diff-map [prefix o n]
  (reduce (fn [a [k new-value]]
            (let [old-value (get o k)]
              (into a (diff (conj prefix k) old-value new-value))))
          []
          n))

(defn- reverse-diffs [ds]
  (keep (fn [[p e]] (when (= e :added) [p :removed])) ds))

(defn model-diff-inform
  "Given paths which are known to have changed and an old and new
  model return an inform message describing all changes to the model."
  [paths o n]
  (vec
   (map (fn [[p e]] [p e o n])
        (mapcat
         (fn [p] (let [ov (get-in o p)
                      nv (get-in n p)]
                  (into (diff p ov nv)
                        (reverse-diffs (diff p nv ov)))))
         paths))))
