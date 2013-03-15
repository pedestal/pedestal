; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

;; This namespace was copied from
;; https://github.com/stuartsierra/cljs-formatter. Once that library
;; has a release, we can add a dependency.

(ns io.pedestal.app.render.push.cljs-formatter
  (:require [domina :as d]
            [domina.xpath :as dx]
            [clojure.string :as string]
            [goog.dom :as gdom]
            [goog.style :as style]
            [goog.color :as color]
            [goog.dom.classes :as classes]
            [goog.events :as events]))

;;; Data to HTML strings

(defn span [class body]
  (str "<span class='" class "'>" body "</span>"))

(defn literal [class x]
  (span class (pr-str x)))

(declare html)

(defn join [separator coll]
  (string/join (span "separator" separator)
               (map html coll)))

(defn html-collection [class opener closer coll]
  (span (str "collection " class)
        (str
         (span "opener" opener)
         (span "contents" (join " " coll))
         (span "closer" closer))))

(defn html-keyval [[k v]]
  (span "keyval"
        (str (html k)
             (span "separator" " ")
             (html v))))

(defn html-keyvals [coll]
  (string/join (span "separator" ", ")
               (map html-keyval coll)))

(defn html-map [coll]
  (span "collection map"
        (str
         (span "opener" "{")
         (span "contents" (html-keyvals coll))
         (span "closer" "}"))))

(defn html-string [s]
  (span "string"
        (str (span "opener" "\"")
             (span "contents" s)
             (span "closer" "\""))))

(defn html [x]
  (cond
   (number? x)  (literal "number" x)
   (keyword? x) (literal "keyword" x)
   (symbol? x)  (literal "symbol" x)
   (string? x)  (html-string x)
   (map? x)     (html-map x)
   (set? x)     (html-collection "set"    "#{" "}" x)
   (vector? x)  (html-collection "vector" "[" "]" x)
   (seq? x)     (html-collection "seq"    "(" ")" x)
   :else        (literal "literal" x)))

;;; DOM layout

(defn overflow? [child parent]
  (let [parent-box (.toBox (style/getBounds parent))
        child-box (.toBox (style/getBounds child))]
    (< (.-right parent-box) (.-right child-box))))


(defn max-inline-width [elem container]
  (let [child (d/single-node elem)
        parent (.-parentNode (d/single-node elem))
        container-node (d/single-node container)
        left-bound (.-left (.toBox (style/getBounds child)))
        parent-right-bound (.-right (.toBox (style/getBounds parent)))
        container-right-bound (.-right (.toBox (style/getBounds container-node)))]
    (- (min parent-right-bound container-right-bound) left-bound)))

(defn width [elem]
  (.-width (style/getBounds (d/single-node elem))))

(declare arrange-element!)

;; Colors chosen with the help of Adobe Kuler
;; http://kuler.adobe.com/
(def initial-arrange-state
  (cycle ["#e6f3f7" "#f2ffff" "#e5f2ff" "#ebf7f4" "#e5fff1"]))

(def color first)

(def next-state rest)

(defn arrange-keyval! [state elem container]
  (let [[key separator val] (d/children elem)]
    (arrange-element! state key container)
    (arrange-element! state val container)
    (when (overflow? elem container)
      (d/set-styles! separator {:display "none"})
      (d/set-styles! key {:display "block"})
      (d/set-styles! val {:display "block"
                          :margin-left "1em"}))))

(def collection-styles
  {:color "black"
   :display "inline-block"
   :padding-top "1px"
   :padding-right "2px"
   :padding-bottom "2px"
   :padding-left "2px"
   :margin-bottom "1ex"
   :border-top-left-radius "5px"
   :border-top-right-radius "10px"
   :border-bottom-right-radius "5px"
   :border-bottom-left-radius "10px"})

(defn arrange-collection! [state elem container]
  (d/add-class! elem "arranged")
  (d/set-styles! elem (merge collection-styles
                             {:background-color (color state)}))
  (let [[opener contents closer] (d/children elem)]
    (d/set-styles! opener {:display "inline"
                           :vertical-align "top"})
    (d/set-styles! closer {:display "inline"
                           :vertical-align "bottom"})
    (d/set-styles! contents {:display "inline-block"
                             :vertical-align "top"})
    (doseq [child (d/children contents)]
      (if (d/has-class? child "separator")
        (d/set-styles! child {:display "none"})
        (do (arrange-element! (next-state state) child container)
            (d/set-styles! child {:display "block"}))))
    ;; Make containing box no wider than it needs to be
    (d/set-styles! elem {:width (str (+ (width contents)
                                        (width opener)
                                        (width closer))
                                     "px")})))

(defn remove-all-styles! [elem]
  ;; remove-attr! doesn't always work
  (d/set-attr! elem :style "")
  (d/remove-class! elem "arranged")
  (doseq [child (d/children elem)]
    (remove-all-styles! child)))

(defn condense-collection! [elem container]
  (let [[opener contents closer] (d/children elem)
        w (- (max-inline-width elem container)
             (* 2 (+ (width opener) (width closer))))]
    (d/set-styles! opener {:font-weight "bold"})
    (d/set-styles! closer {:font-weight "bold"})
    (d/set-styles! contents {:color "gray"
                             :display "inline-block"
                             :max-width (str w "px")
                             :overflow "hidden"
                             :text-overflow "ellipsis"})))

(defn arrange-element! [state elem container]
  (remove-all-styles! elem)
  (d/set-styles! elem {:white-space "pre"})
  (when (overflow? elem container)
    (cond
     (d/has-class? elem "collection")
     (if (d/has-class? elem "condensed")
       (condense-collection! elem container)
       (arrange-collection! state elem container))
     (d/has-class? elem "keyval")
     (arrange-keyval! state elem container))))

(defn arrange! [elem container]
  (arrange-element! initial-arrange-state elem container))

(defn find-arranged-parent [elem container]
  (cond (= container elem)
          elem
        (and (gdom/isElement elem)
             (d/has-class? elem "collection")
             (d/has-class? elem "arranged"))
          elem
        :else
          (recur (.-parentNode elem) container)))

(defn toggle! [target-elem arranged-elem container]
  (if (d/has-class? target-elem "condensed")
    (d/remove-class! target-elem "condensed")
    (d/add-class! target-elem "condensed"))
  (arrange! arranged-elem container))

(defn set-toggle-on-click! [elem container]
  (events/listen (d/single-node elem) "click"
                 (fn [event]
                   (loop [t (.-target event)]
                     (when t
                       (if (and (gdom/isElement t)
                                (d/has-class? t "collection")
                                (or (d/has-class? t "condensed")
                                    (d/has-class? t "arranged")))
                         (do (.stopPropagation event)
                             (.preventDefault event)
                             (toggle! t elem container))
                         (recur (.-parentNode t))))))))
