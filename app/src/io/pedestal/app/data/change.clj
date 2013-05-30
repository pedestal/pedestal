; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app.data.change)

(defn- find-changes [changes old-map new-map path]
  (let [parent-path (butlast path)
        k (last path)
        old-m (if (seq parent-path) (get-in old-map parent-path) old-map)
        new-m (if (seq parent-path) (get-in new-map parent-path) new-map)
        o (get old-m k)
        n (get new-m k)]
    (cond (not (contains? old-m k)) (update-in changes [:added] (fnil conj #{}) path)

          (not (contains? new-m k)) (update-in changes [:removed] (fnil conj #{}) path)

          (and (not= o n) (or (not (map? o)) (not (map? n))))
          (update-in changes [:updated] (fnil conj #{}) path)

          (and (not= o n) (map? o) (map? n))
          (reduce (fn [a k]
                    (find-changes a old-map new-map (conj path k)))
                  changes
                  (into (keys n) (keys o)))

          :else changes)))

(defn- merge-changes [c1 c2]
  (merge-with (comp set concat) c1 c2))

(defn- descendent? [path-a path-b]
  (let [[small large] (if (< (count path-a) (count path-b))
                        [path-a path-b]
                        [path-b path-a])]
    (= small (take (count small) large))))

(defn- remove-redundent-updates [updates]
  (reduce (fn [a update]
            (if (some #(descendent? % update) a)
              a
              (conj a update)))
          #{}
          (reverse (sort-by count updates))))

(defn- remove-redundent-adds [adds]
  (reduce (fn [a add]
            (if (some #(descendent? % add) a)
              a
              (conj a add)))
          #{}
          (sort-by count adds)))

(defn- remove-updates-covered-by-adds [updates adds]
  (set (remove (fn [u]
                 (some (fn [a]
                         (descendent? a u))
                       adds))
               updates)))

(defn- remove-updates-covered-by-removes [updates removes]
  (set (remove (fn [u]
                 (some (fn [r]
                         (descendent? r u))
                       removes))
               updates)))

(defn compact [old-m new-m {:keys [added updated removed inspect] :as change}]
  (let [change (reduce (fn [a change-path]
                         (find-changes a old-m new-m change-path))
                       change
                       inspect)
        change (if (:updated change)
                 (update-in change [:updated] remove-redundent-updates)
                 change)
        change (if (:added change)
                 (update-in change [:added] remove-redundent-adds)
                 change)
        change (if (:updated change)
                 (update-in change [:updated] remove-updates-covered-by-adds (:added change))
                 change)
        change (if (:updated change)
                 (update-in change [:updated] remove-updates-covered-by-removes (:removed change))
                 change)]
    (reduce (fn [a [k v]]
              (if (empty? v)
                a
                (assoc a k v)))
            {}
            (dissoc change :inspect))))
