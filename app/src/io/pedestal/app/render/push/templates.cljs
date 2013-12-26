; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.render.push.templates
  (:require [io.pedestal.app.render.push :as render]
            [domina :as d]))

(defn sibling [path segment]
  (conj (vec (butlast path)) segment))

(defn parent [path]
  (vec (butlast path)))

(defn update-template [t m]
  (doseq [[k v] m {:keys [id type attr-name]} (get t k)]
    (case type 
      :attr (if (nil? v)
              (d/remove-attr! (d/by-id id) attr-name)
              (d/set-attrs! (d/by-id id) {attr-name v}))
      :content (d/set-html! (d/by-id id) v)
      nil)))

(defn- add-in-template [f t m]
  (doseq [[k v] m {:keys [id type]} (get t k)]
    (assert (= :content type)
            "You may only add to content")
    (f (d/by-id id) v)))

(defn update-t [r path data]
  (let [template (render/get-data r (conj path ::template))]
    (update-template template data)))

(defn prepend-t [r path data]
  (let [template (render/get-data r (conj path ::template))]
    (add-in-template d/prepend! template data)))

(defn insert-t [r path data idx]
  (let [template (render/get-data r (conj path ::template))]
    (add-in-template #(d/insert! %1 %2 idx) template data)))

(defn append-t [r path data]
  (let [template (render/get-data r (conj path ::template))]
    (add-in-template d/append! template data)))

(defn update-parent-t [r path data]
  (let [template (render/get-data r (conj (parent path) ::template))]
    (update-template template data)))

(defn add-template [r path make-template]
  (let [[template html] (make-template)]
    (render/set-data! r (conj path ::template) template)
    (fn [data]
      (doseq [[k v] data]
        (let [info (get template k)]
          (when (some (comp (partial = "id") :attr-name) info)
            (render/set-data! r
                              (conj path ::template)
                              (assoc template k (mapv #(assoc % :id v) info))))))
      (html data))))
