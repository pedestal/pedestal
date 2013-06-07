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
  (doseq [[k v] t {:keys [id type attr-name]} v]
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
    (assert (every? (fn [info] (= :content (:type info))) (get t k))
            "You may only add to content")
    (when (contains? t k)
      (doseq [info (get t k)]
        (f (d/by-id (:id info)) v)))))

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
    html))
