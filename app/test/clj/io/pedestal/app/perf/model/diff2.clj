; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app.perf.model.diff2)

(declare diff-map)

;; We need to have a shorter way to report large changes like adding
;; or removing a subtree.
(comment

  ;; for example the following could mean that everything under
  ;; [:a :b :c] has been added

  [[:a :b :c :**] :added]

  )

(declare diff-map)

(defn- diff [prefix o n]
  (cond (identical? o n) nil

        ;; possible maps

        (and (map? o) (map? n)) (diff-map prefix o n)

        (and (nil? o) (map? n)) (diff-map prefix o n)

        (and (map? o) (nil? n)) nil

        (or (map? n) (map? o)) [[prefix :updated]]

        ;; no more maps

        (= o n) nil

        (nil? o) [[prefix :added]]

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

(defn diff-paths [paths o n]
  (mapcat
   (fn [p] (let [ov (get-in o p)
                nv (get-in n p)]
            (into (diff p ov nv)
                  (reverse-diffs (diff p nv ov)))))
   paths))

(defn model-diff-inform
  "Given paths which are known to have changed and an old and new
  model return an inform message describing all changes to the model."
  ([o n]
     (model-diff-inform (mapv vector (concat (keys n) (keys o))) o n))
  ([paths o n]
     (mapv (fn [[p e]] [p e o n]) (diff-paths paths o n))))
