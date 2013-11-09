; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app.match
  "Path matching.

  Source paths are vectors. For example:

  [:a :b :c :d]

  Message keys are keywords. For example:

  :click, :submit, :added, :removed

  A configuration is provided which maps items to message patterns.

  [[item-a [:path1] :*]
   [item-b [:path2] :click]
   [item-c [:path3] :* [:path4] :*]]

  Given a path and key for a message, this map is used to find
  matching items.

  A matching path could be an identical path like

  [:a :b :c :d]

  Path patterns may also contain wildcard keywords :* and :**. :*
  matches any path element and :** matches zero or more path elements.

  We could match the path above with the following patterns:

  [:a :b :c :d]
  [:a :b :c :*]
  [:a :* :c :*]
  [:a :* :* :*]
  [:a :**]
  [:**]

  The :* wildcard can be used to match any event.")

(defn- normalized-config [config]
  (for [[item patterns] (map (fn [[f & ps]] [f (partition 2 ps)]) config)
        p patterns]
    [(apply conj p) item (set (map vec patterns))]))

(defn- update-index [idx f config]
  (assert (every? #(and (odd? (count %)) (> (count %) 1)) config)
          "Each config vector must have at least one pattern and contain an odd number of items")
  (reduce (fn [tree [pattern item inputs]]
            (update-in tree (conj pattern ::items) f [item inputs]))
          idx
          (normalized-config config)))

(defn index
  "Given a configuration, return an index which allows quick matching
  of messages to items in the index."
  ([config]
     (index {} config))
  ([idx config]
     (update-index idx (fnil conj []) config)))

(defn- remove-item [coll i]
  (vec (remove #(= % i) coll)))

(defn remove-from-index [idx config]
  (update-index idx remove-item config))

(defn- merge-items [results event-entry items]
  (into results (mapv (fn [f] (conj f event-entry)) items)))

(defn- match-items*
  "See doc string for match-items."
  [idx [p & ps] event-entry results]
  (let [sub-indecies (select-keys idx [p :*])
        results (if (contains? idx :**)
                  (into results (match-items* (get idx :**)
                                              [(if (seq ps) (last ps) p)]
                                              event-entry
                                              results))
                  results)]
    (if (nil? p)
      (merge-items results event-entry (::items idx))
      (reduce (fn [r i]
                (into r (match-items* i ps event-entry r)))
              results
              (vals sub-indecies)))))

(defn match-items
  "Given an index and a message, return all the items from the index
  which match this event. Returns a set of vectors. Each vector has
  the format

  [item patterns message]

  where patterns is the set of patterns that matched this message."
  [idx [source event :as entry]]
  (match-items* idx (conj source event) entry #{}))

(defn match
  "Given an index and an inform or transform message, return all of
  the items which match this message along with the part of the
  message which matches the item. Returns a set of vectors. Each
  vector has the format

  [item patterns message]

  where patterns is the set of patterns that matched this message."
  [idx inform]
  (let [items (mapcat (fn [i] (match-items idx i)) inform)]
    (set (map (fn [[[item ins] infs]] [item ins infs])
              (reduce (fn [a [item inputs inform]]
                        (let [k [item inputs]]
                          (if (contains? a k)
                            (update-in a [k] conj inform)
                            (assoc a k [inform]))))
                      {}
                      items)))))
