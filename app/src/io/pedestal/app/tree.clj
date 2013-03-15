; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app.tree
    "A tree structure which can be used to represent the user
     interface of an application.

    A tree node may contain a value, attributes and data model
    transformations.

    Values are arbitrary Clojure data.

    Attributes are a map of keys to values.

    Data model transformations are maps of transform names to
    collections of messages which can transform the data model.

    The purpose of this tree is to provide an information model for an
    application. This model contains all of the information that is
    required to render a user interface without including information
    about how to render it."
    (:require [clojure.set :as set]
              [io.pedestal.app.util.log :as log]
              [io.pedestal.app.query :as query]))

(def ^:dynamic *gc-deltas* true)

(defmulti inverse (fn [delta] (first delta)))

(defmethod inverse :node-create [[op path type]]
  [:node-destroy path type])

(defmethod inverse :node-destroy [[op path type]]
  [:node-create path type])

(defmethod inverse :value [[op path o n]]
  [op path n o])

(defmethod inverse :attr [[op path k o n]]
  [op path k n o])

(defmethod inverse :transform-enable [[op path transform-name msgs]]
  [:transform-disable path transform-name msgs])

(defmethod inverse :transform-disable [[op path transform-name msgs]]
  [:transform-enable path transform-name msgs])

(defn invert [deltas]
  (mapv inverse (reverse deltas)))

(defn- real-path [path]
  (vec (interpose :children (into [:tree] path))))

(defn- new-node [children]
  {:children children})

(defn- node-type [x]
  (cond (map? x) :map
        (vector? x) :vector
        :else :unknown))

(defn- existing-node-has-same-type? [tree r-path type]
  (if-let [node (get-in tree r-path)]
    (= (node-type (:children node)) type)
    true))

(defn- parent-exists? [tree path]
  (if (= path [])
    true
    (let [r-path (real-path (vec (butlast path)))]
      (get-in tree r-path))))

(defn- apply-to-tree-dispatch [_ delta]
  (if (fn? delta)
    :function
    (first delta)))

(defmulti ^:private apply-to-tree apply-to-tree-dispatch)

(defmethod apply-to-tree :default [tree _]
  tree)

(declare map->deltas)

(defmethod apply-to-tree :node-create [tree delta]
  (let [[_ path type] delta]
    (if (map? type)
      (reduce apply-to-tree tree (map->deltas type path))
      (let [type (or type :map)
            delta (if (= (count delta) 2) [:node-create path type] delta)
            r-path (real-path path)
            children (condp = type
                       :vector []
                       :map {})
            tree (if (parent-exists? tree path)
                   tree
                   (let [children-type (if (keyword? (last path)) :map :vector)]
                     (apply-to-tree tree [:node-create (vec (butlast path)) children-type])))]
        (assert (parent-exists? tree path)
                (str "The parent of " path " does not exist."))
        (assert (existing-node-has-same-type? tree r-path type)
                (str "The node at " path " exists and is not the same type as the requested node.\n"
                     "node:\n"
                     (get-in tree r-path) "\n"
                     "delta:\n"
                     delta))
        (if (get-in tree r-path)
          tree
          (-> tree
              (assoc-in r-path (new-node children))
              (update-in [:this-tx] conj delta)))))))

(defn- remove-index-from-vector [vector index]
  (let [[begin end] (split-at index vector)]
    (into (vec begin) (rest end))))

(defn- child-keys [children]
  (condp = (node-type children)
    :map (keys children)
    :vector (reverse (range (count children)))
    :else []))

(defn- remove-children [tree path children]
  (reduce apply-to-tree tree (map (fn [k] [:node-destroy (conj path k)])
                                  (child-keys children))))

(defmethod apply-to-tree :node-destroy [tree delta]
  (let [[_ path type] delta
        r-path (real-path path)
        containing-path (butlast r-path)
        node-to-remove (get-in tree r-path)
        children (:children node-to-remove)
        type (or type (node-type children))
        delta (if (= (count delta) 2) (conj delta type) delta)]
    (if (not node-to-remove)
      tree
      (do (assert (= (node-type children) type)
                  (str "The given child node type does not match the actual type: "
                       (pr-str delta)))
          (let [tree (if (not (empty? children))
                       (remove-children tree path children)
                       tree)
                tree (if (:value node-to-remove)
                       (apply-to-tree tree [:value path (:value node-to-remove) nil])
                       tree)
                tree (if-let [ks (:transforms node-to-remove)]
                       (reduce apply-to-tree tree (map (fn [[k v]] [:transform-disable path k]) ks))
                       tree)
                tree (if-let [ks (:attrs node-to-remove)]
                       (reduce apply-to-tree tree (map (fn [[k v]] [:attr path k v nil]) ks))
                       tree)
                new-tree (if (nil? containing-path)
                           (assoc tree :tree nil)
                           (let [last-path (last r-path)
                                 container (get-in tree containing-path)]
                             (if (map? container)
                               (update-in tree containing-path dissoc last-path)
                               (update-in tree containing-path remove-index-from-vector last-path))))]
            (update-in new-tree [:this-tx] conj delta))))))

(defmethod apply-to-tree :children-exit [tree delta]
  (let [[_ path] delta
        r-path (real-path path)
        c-path (conj r-path :children)
        children (get-in tree c-path)]
    (if (not (empty? children))
      (remove-children tree path children)
      tree)))

(defn- same-value? [tree path v]
  (= (get-in tree path) v))

(defn update-or-remove [tree path v]
  (if (nil? v)
    (update-in tree (butlast path) dissoc (last path))
    (assoc-in tree path v)))

(defmethod apply-to-tree :value [tree delta]
  (let [[op path o n] delta
        r-path (real-path path)
        v-path (conj r-path :value)
        old-value (get-in tree v-path)
        [o n] (if (= (count delta) 4) [o n] [old-value o])]
    (assert (= o old-value)
            (str "The old value at path " path " is " old-value
                 " but was expected to be " o "."))
    (if (= o n)
      tree
      (-> tree
          (update-or-remove v-path n)
          (update-in [:this-tx] conj [op path o n])))))

(defn remove-empty [tree path]
  (if (seq (get-in tree path))
    tree
    (update-in tree (butlast path) dissoc (last path))))

(defmethod apply-to-tree :attr [tree delta]
  (let [[op path k o n] delta
        r-path (real-path path)
        a-path (conj r-path :attrs k)
        old-value (get-in tree a-path)
        [o n] (if (= (count delta) 5) [o n] [old-value o])]
    (assert (= o old-value)
            (str "Error:" (pr-str delta) "\n"
                 "The old attribute value for " k " is "
                 old-value
                 " but was expected to be " o "."))
    (if (= o n)
      tree
      (-> tree
          (update-or-remove a-path n)
          (remove-empty (conj r-path :attrs))
          (update-in [:this-tx] conj [op path k o n])))))

(defn- same-transform? [tree path msgs]
  (= (get-in tree path) msgs))

(defmethod apply-to-tree :transform-enable [tree delta]
  (let [[_ path k msgs] delta
        r-path (real-path path)
        e-path (conj r-path :transforms k)]
    (assert (or (not (get-in tree e-path))
                (same-transform? tree e-path msgs))
            (str "A different transform " k " at path " path " already exists."))
    (if (get-in tree e-path)
      tree
      (-> tree
          (assoc-in e-path msgs)
          (update-in [:this-tx] conj delta)))))

(defmethod apply-to-tree :transform-disable [tree delta]
  (let [[_ path k] delta
        r-path (real-path path)
        transforms-path (conj r-path :transforms)
        e-path (conj transforms-path k)]
    (if (get-in tree e-path)
      (-> tree
          (update-in [:this-tx] conj (conj delta (get-in tree e-path)))
          (update-in transforms-path dissoc k)
          (remove-empty transforms-path))
      tree)))

(defn- node-deltas [{:keys [value transforms attrs]} path]
  (concat []
          (when value [[:value path value]])
          (when attrs (vec (map (fn [[k v]]
                                  [:attr path k v])
                                attrs)))
          (when transforms (vec (map (fn [[k v]]
                                   [:transform-enable path k v])
                                 transforms)))))

(defn- map->deltas [tree path]
  (let [node-keys #{:children :transforms :value :attrs}
        node? (and (map? tree) (not (empty? (set/intersection (set (keys tree)) node-keys))))
        children (if node? (or (:children tree) {}) tree)
        children-type (node-type children)
        node-deltas (when node?
                      (node-deltas tree path))]
    (concat [[:node-create path children-type]]
            node-deltas
            (mapcat (fn [k]
                      (map->deltas (get-in tree (if node? [:children k] [k])) (conj path k)))
                    (cond (= children-type :map)
                          (keys children)
                          (= children-type :vector)
                          (range (count children))
                          :else [])))))

(defn expand-map [delta]
  (if (map? delta)
    (map->deltas delta [])
    [delta]))

(defn- expand-maps [deltas]
  (mapcat expand-map deltas))

(defn- update-tree
  "Update the tree and return the actual deltas which were used to
  update the tree. A single delta can be expanded into multiple
  deltas."
  [tree deltas]
  (reduce apply-to-tree tree deltas))

(defmethod apply-to-tree :function [tree f]
  (let [deltas (f tree)]
    ;; TODO: What do we do if f causes an error
    (update-tree tree deltas)))

;; Query
;; ================================================================================

(def ^:private next-eid-atom (atom 0))

(defn- next-eid []
  (swap! next-eid-atom inc))

(defn- transform->entities [transform-name msgs node-id]
  (let [transform-id (next-eid)]
    (concat [{:t/id transform-id :t/transform-name transform-name :t/node node-id :t/type :t/transform}]
            (map (fn [m] (merge m {:t/id (next-eid) :t/transform transform-id :t/type :t/message})) msgs))))

(defn- transforms->entities [transforms node-id]
  (reduce (fn [acc [transform-name msgs]]
            (concat acc (transform->entities transform-name msgs node-id)))
          []
          transforms))

(defn- attrs->entities [attrs node-id]
  (when (not (empty? attrs)) [(merge attrs {:t/id (next-eid) :t/node node-id :t/type :t/attrs})]))

(defn- node->entities [node path parent-id node-id]
  (let [{:keys [value attrs transforms]} node
        node-e {:t/id node-id :t/path path :t/type :t/node :t/segment (last path)}
        node-e (if parent-id
                 (assoc node-e :t/parent parent-id)
                 node-e)
        node-e (if value
                 (assoc node-e :t/value value)
                 node-e)
        attrs-es (attrs->entities attrs node-id)
        transform-es (transforms->entities transforms node-id)]
    (concat [node-e] attrs-es transform-es)))

(defn- tree->entities [tree path parent-id]
  (let [{:keys [children]} tree
        ks (child-keys children)
        node-id (next-eid)
        node-tuples (node->entities tree path parent-id node-id)]
    (concat node-tuples
            (mapcat (fn [k] (tree->entities (get-in tree [:children k]) (conj path k) node-id))
                    ks))))

(defn- entity->tuples [e]
  (let [id (:t/id e)]
    (map (fn [[k v]] [id k v]) (dissoc e :t/id))))

(defn- entities->tuples [entities]
  (mapcat entity->tuples entities))

(defn- tree->tuples [tree]
  (if (:tree tree)
    (entities->tuples
     (tree->entities (:tree tree) [] nil))
    []))

(defrecord Tree []
  query/TupleSource
  (tuple-seq [this]
    (tree->tuples this)))

(defn delete-deltas [t deltas]
  (reduce (fn [d k]
            (if (< k t)
              (do (log/debug :gc (str "GC: Deleting " (count (get d k)) " deltas at time " k))
                  (dissoc d k))
              d))
          deltas
          (keys deltas)))

(defn gc [state]
  (if *gc-deltas*
    (do (log/debug :gc "GC: Running...")
        (let [t (:t state)
              delete-t (- t 2)]
          (log/debug :gc (str "GC: Deleting t < " delete-t))
          (log/debug :gc (str "GC: There are currently "
                              (count (apply concat (vals (:deltas state))))
                              " deltas."))
          (update-in state [:deltas] (partial delete-deltas delete-t))))
    (do (log/debug :gc (str "GC is turned off. There are "
                            (count (apply concat (vals (:deltas state))))
                            " accumulated deltas"))
        state)))

;; Public API
;; ================================================================================

(defn apply-deltas
  "Given an old tree and a sequence of deltas, return an updated tree.
  The deltas can be a sequence of tuples or a map which can be
  expanded into a sequence of tuples.

  The keyword :commit can be inserted into the stream of deltas to force
  a commit at a specific point."
  [old deltas]
  (let [{:keys [seq t]} old
        deltas (expand-maps deltas)
        {:keys [tree this-tx]} (update-tree old deltas)
        deltas (map (fn [d s]
                      {:delta d
                       :t t
                       :seq s})
                    this-tx
                    (iterate inc seq))]
    (-> old
        (assoc-in [:deltas t] deltas)
        (assoc-in [:this-tx] [])
        (update-in [:seq] + (count deltas))
        (assoc-in [:tree] tree)
        (update-in [:t] inc))))

(defn value [tree path]
  (let [r-path (real-path path)]
    (get-in tree (conj r-path :value))))

(defn node-exists? [tree path]
  (let [r-path (real-path path)]
    (get-in tree r-path)))

(def new-app-model
  (map->Tree
   {:deltas {}           ;; all changes made to the tree indexed by time
    :this-tx []          ;; in-transaction deltas
    ;; Outside of a transaction this will always be empty, in a
    ;; transaction it will accumulate deltas to be committed at the
    ;; end of the transaction.
    :tree nil            ;; the current tree
    :seq 0               ;; the next available seq number
    :t 0                 ;; the next available transaction number
    }))

(defn t
  "Get the current tree time value."
  [tree]
  (:t tree))

(defn since-t
  "Get all deltas since time t, inclusive."
  [tree t]
  (let [ts (range t (:t tree))]
    (vec (map :delta (mapcat #(get (:deltas tree) %) ts)))))
