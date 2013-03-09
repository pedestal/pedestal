;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.app.render.push.templates
  (:require [io.pedestal.app.render.push :as render]
            [domina :as d]))

(defn sibling [path segment]
  (conj (vec (butlast path)) segment))

(defn parent [path]
  (vec (butlast path)))

(defn update-template [t m]
  (doseq [[k {:keys [id type attr-name]}] t]
    (case type
      :attr (cond (and (contains? m k) (nil? (get m k)))
                  (d/remove-attr! (d/by-id id) attr-name)
                  (contains? m k)
                  (d/set-attrs! (d/by-id id) {attr-name (get m k)}))
      :content (when (contains? m k)
                 (d/set-html! (d/by-id id) (get m k)))
      nil)))

(defn- add-in-template [f t m]
  (doseq [[k v] m]
    (assert (= (get-in t [k :type]) :content)
            "You may only add to content.")
    (when (contains? t k)
      (f (d/by-id (get-in t [k :id])) v))))

(defn update-t [r path data]
  (let [template (render/get-data r (conj path ::template))]
    (update-template template data)))

(defn prepend-t [r path data]
  (let [template (render/get-data r (conj path ::template))]
    (add-in-template d/prepend! template data)))

(defn append-t [r path data]
  (let [template (render/get-data r (conj path ::template))]
    (add-in-template d/append! template data)))

(defn update-parent-t [r path data]
  (let [template (render/get-data r (conj (parent path) ::template))]
    (update-template template data)))

(defn add-template [r path make-template]
  (let [[template html] (make-template)]
    (render/set-data! r (conj path ::template) template)
    html))
