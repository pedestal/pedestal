; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:shared io.pedestal.app.render.push
  "A Renderer implementation for the DOM which supports push
  rendering. Provides functions which help to map an application model
  to the DOM."
  (:require [io.pedestal.app.protocols :as p]
            [io.pedestal.app.util.platform :as platform]
            [io.pedestal.app.util.log :as log]
            [io.pedestal.app.tree :as tree]))

;; Handlers
;; ================================================================================

(def ^:private search-ops {:node-create #{:node-* :*}
                           :node-destroy #{:node-* :*}
                           :value #{:value :*}
                           :attr #{:attr :*}
                           :transform-enable #{:transform-* :*}
                           :transform-disable #{:transform-* :*}})

(defn- real-path [op path]
  (cons op (conj (vec (interleave (repeat :children) path)) :handler)))

(defn add-handler [handlers op path f]
  (assoc-in handlers (real-path op path) f))

(defn add-handlers
  ([hs]
     (add-handlers {} hs))
  ([m hs]
     (reduce (fn [acc [op path f]]
               (add-handler acc op path f))
             m
             hs)))

(defn- matching-keys [ks p]
  (filter (fn [k]
            (or (= k p)
                (= k :*)
                (= k :**)
                (when (contains? search-ops p)
                  (contains? (p search-ops) k))))
          ks))

(defn- sort-keys [ks]
  (let [sorted-keys (remove #(= % :**) (sort ks))]
    (reverse (if (> (count ks) (count sorted-keys))
               (conj sorted-keys :**)
               sorted-keys))))

(defn- select-matches [handlers p]
  (let [keys (matching-keys (keys handlers) p)]
    (map (fn [k] [k (get handlers k)]) (sort-keys keys))))

(defn- find-handler* [handlers path]
  (if (empty? path)
    (:handler handlers)
    (some (fn [[k v]]
            (if-let [handler (find-handler* v (rest path))]
              handler
              (when (= k :**) (:handler v))))
          (select-matches (:children handlers) (first path)))))

(defn find-handler [handlers op path]
  (find-handler* {:children handlers} (vec (cons op path))))

;; Rendering
;; ================================================================================

(defprotocol DomMapper
  (get-id [this path])
  (get-parent-id [this path])
  (new-id! [this path] [this path v]
    "Create a new id for this given path. Store this id in the renderer's environment.
    Returns the generated id. An id can be provided as a third
    argument.")
  (delete-id! [this path]
    "Delete this id and all information associated with it from the
    environment. This will also delete all ids and information
    associated with child nodes.")
  (on-destroy! [this path f]
    "Add a function to be called when the node at path is destroyed.")
  (set-data! [this ks d])
  (drop-data! [this ks])
  (get-data [this ks]))

(defn- run-on-destroy!
  "Given a node in the environement which is going to be deleted, run all on-destroy
  functions in the tree."
  [env]
  (let [nodes (tree-seq (constantly true)
                        (fn [n]
                          (map #(get n %) (remove #{:id :on-destroy :_data} (keys n))))
                        env)]
    (doseq [f (mapcat :on-destroy nodes)]
      (f))))

(defrecord DomRenderer [env]
  DomMapper
  (get-id [this path]
    (if (seq path)
      (get-in @env (conj path :id))
      (:id @env)))
  (get-parent-id [this path]
    (when (seq path)
      (get-id this (vec (butlast path)))))
  (new-id! [this path]
    (new-id! this path (gensym)))
  (new-id! [this path v]
    (log/info :in :new-id! :msg (str "creating new id " v " at path " path))
    (swap! env assoc-in (conj path :id) v)
    v)
  (delete-id! [this path]
    (run-on-destroy! (get-in @env path))
    (swap! env assoc-in path nil))
  (on-destroy! [this path f]
    (swap! env update-in (conj path :on-destroy) (fnil conj []) f))
  (set-data! [this ks d]
    ;; TODO: Use namespaced keywords
    (swap! env assoc-in (concat [:_data] ks) d))
  (drop-data! [this ks]
    (swap! env update-in (concat [:_data] (butlast ks)) dissoc (last ks)))
  (get-data [this ks]
    (get-in @env (concat [:_data] ks))))

(defn renderer
  ([root-id handlers]
     (renderer root-id handlers identity))
  ([root-id handlers log-fn]
     (let [handlers (if (vector? handlers) (add-handlers handlers) handlers)
           renderer (->DomRenderer (atom {:id root-id}))]
       (fn [deltas input-queue]
         (log-fn deltas)
         (doseq [d deltas]
           (let [[op path] d
                 handler (find-handler handlers op path)]
             (when handler (handler renderer d input-queue))))))))
