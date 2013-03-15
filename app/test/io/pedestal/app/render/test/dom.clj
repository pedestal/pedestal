; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

;; Copyright (c) Brenton Ashworth and Relevance, Inc. All rights reserved.
;; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.render.test.dom
  "In order to test the rendering code, it would be nice to write
  rendering functions which manipulate the DOM. This namespace
  provides something that is close enough to the DOM for our tests to be
  realistic.

  The goal here is to make something as bad as the DOM, not to make a nice
  functional data structure.

  This namespace includes tests which ensure that the provided DOM
  functions are working properly."
  (:require [io.pedestal.app.render.push :as render])
  (:use clojure.test))

(def ^:dynamic *dom*)

(def empty-node {:content nil
                 :attrs {}
                 :children []})

(defn fresh-dom [root-id]
  {:window {}
   :root (assoc-in empty-node [:attrs :id] root-id)})

(defn test-dom []
  (atom (fresh-dom :root)))

(defn- dom-node-seq [path node]
  (lazy-seq
   (cons [(get-in node [:attrs :id]) path]
         (apply concat
                (map-indexed (fn [i x] (dom-node-seq (conj path :children i) x))
                             (:children node))))))

(defn- dom-seq
  "Take a DOM tree and turn it into a lazy sequence of tuples where
  the first element is the id and the second element is the path to
  the node with that id."
  [dom]
  (dom-node-seq [:root] (:root dom)))

(def simple-dom {:root {:content "Hello World"
                        :attrs {:id :a}
                        :children [{:content nil
                                    :attrs {:id :b}
                                    :children [{:content nil
                                                :attrs {:id :d}
                                                :children []}]}
                                   {:content nil
                                    :attrs {:id :c}
                                    :children []}]}})

(deftest test-dom-seq
  (is (= (dom-seq {:root {:content "Hello World"
                          :attrs {:id "root"}
                          :children []}})
         [["root" [:root]]]))
  (is (= (dom-seq {:root {:content "Hello World"
                          :attrs {:id :a}
                          :children [{:content nil
                                      :attrs {:id :b}
                                      :children []}
                                     {:content nil
                                      :attrs {:id :c}
                                      :children []}]}})
         [[:a [:root]]
          [:b [:root :children 0]]
          [:c [:root :children 1]]]))
  (is (= (dom-seq simple-dom)
         [[:a [:root]]
          [:b [:root :children 0]]
          [:d [:root :children 0 :children 0]]
          [:c [:root :children 1]]])))

(defn- path-to [dom id]
  (some (fn [[node-id path]] (when (= node-id id) path))
        (dom-seq dom)))

(deftest test-path-to
  (is (= (path-to simple-dom :a)
         [:root]))
  (is (= (path-to simple-dom :d)
         [:root :children 0 :children 0])))

(defn append!
  "An append function which has side-effects in the DOM"
  [id content]
  (let [path (path-to @*dom* id)]
    (swap! *dom* update-in (conj path :children) (fnil conj []) content)))

(defn- remove-id [coll id]
  (remove #(= (get-in % [:attrs :id]) id) coll))

(defn destroy!
  "Destroy a node in the DOM."
  [id]
  (let [path (path-to @*dom* id)]
    (swap! *dom* update-in (butlast path) remove-id id)))

(defn nth-child-id [parent n]
  (let [path (path-to @*dom* parent)]
    (:id (:attrs (nth (get-in @*dom* (conj path :children)) n)))))

(defn destroy-children!
  "Destroy all the children of this node in the DOM."
  [id]
  (let [path (path-to @*dom* id)]
    (swap! *dom* update-in (conj path :children) (constantly []))))

(defn set-attrs!
  "Set attributes for a DOM node."
  [id attrs]
  (let [path (path-to @*dom* id)]
    (swap! *dom* update-in (conj path :attrs) merge attrs)))

(defn set-content!
  "Set the content for a DOM node."
  [id v]
  (let [path (path-to @*dom* id)]
    (swap! *dom* assoc-in (conj path :content) v)))

(deftest test-append!
  (binding [*dom* (atom (fresh-dom "root"))]
    (append! "root" {:content "Hello World"})
    (is (= (:root @*dom*)
           {:content nil
            :attrs {:id "root"}
            :children [{:content "Hello World"}]})))

  (binding [*dom* (atom (fresh-dom :a))]
    (append! :a {:content "b" :attrs {:id :b}})
    (append! :a {:content "c" :attrs {:id :c}})
    (append! :b {:content "d" :attrs {:id :d}})
    (is (= (:root @*dom*)
           {:content nil
            :attrs {:id :a}
            :children [{:content "b" :attrs {:id :b} :children [{:content "d" :attrs {:id :d}}]}
                       {:content "c" :attrs {:id :c}}]}))))

(deftest test-destroy!
  (binding [*dom* (atom {:root {:content nil
                                :attrs {:id :a}
                                :children [{:content "b" :attrs {:id :b}
                                            :children [{:content "d" :attrs {:id :d}}]}
                                           {:content "c" :attrs {:id :c}}]}})]
    (destroy! :d)
    (is (= (:root @*dom*)
           {:content nil
            :attrs {:id :a}
            :children [{:content "b" :attrs {:id :b}
                        :children []}
                       {:content "c" :attrs {:id :c}}]}))
    (destroy! :c)
    (is (= (:root @*dom*)
           {:content nil
            :attrs {:id :a}
            :children [{:content "b" :attrs {:id :b}
                        :children []}]}))
    (destroy! :b)
    (is (= (:root @*dom*)
           {:content nil
            :attrs {:id :a}
            :children []}))))

(deftest test-set-attrs!
  (binding [*dom* (atom {:root {:content nil
                                :attrs {:id :a}
                                :children [{:content "b" :attrs {:id :b}
                                            :children [{:content "d" :attrs {:id :d}}]}
                                           {:content "c" :attrs {:id :c}}]}})]
    (set-attrs! :d {:class "button"})
    (is (= (:root @*dom*)
           {:content nil
            :attrs {:id :a}
            :children [{:content "b" :attrs {:id :b}
                        :children [{:content "d" :attrs {:id :d :class "button"}}]}
                       {:content "c" :attrs {:id :c}}]}))))

(defn default-exit [r [_ path] _]
  (destroy! (render/get-id r path))
  (render/delete-id! r path))

(defn click! [id]
  (when-let [f (get-in @*dom* [:window :events id :click])]
    (f nil)))

(defn listen! [id event f]
  (swap! *dom* assoc-in [:window :events id event] f))

(defn unlisten! [id event]
  (swap! *dom* update-in [:window :events id] dissoc event))
