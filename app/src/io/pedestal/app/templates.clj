; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.templates
  "HTML templating for Clojure and ClojureScript.

  * Combine HTML fragments into complete documents

  * Extract HTML fragments from documents for use in ClojureScript

  * Generate functions which return 'filled-in' HTML when passed a map
    of values

  * Generate functions/data which can be used to dynamically updated
    templates after they have been added to the DOM

  The functions in this namespace are either called from Clojure and
  used from macros to produce functions or data which can be used in
  ClojureScript.
  "
  (:use net.cgrand.enlive-html)
  (:require [clojure.string :as string])
  (:import java.io.File))

(defn render
  "Given a seq of Enlive nodes, return the corresponding HTML string."
  [t]
  (apply str (emit* t)))

(declare construct-html)

;; We need to use this instead of Enlive's html-snippet, because
;; html-snippet throws away the doctype
(defn html-parse
  "Parse a string into a seq of Enlive nodes."
  [s]
  (html-resource (java.io.StringReader. s)))

(defn- html-body [name]
  (let [nodes (html-resource name)]
    (or
     (:content (first (select nodes [:body])))
     nodes)))

(defn- include-html [h]
  (let [includes (select h [:_include])]
    (loop [h h
           includes (seq includes)]
      (if includes
        (let [file (-> (first includes) :attrs :file)
              include (construct-html (html-body file))]
          (recur (transform h [[:_include (attr= :file file)]] (substitute include))
                 (next includes)))
        h))))

(defn- maps [c] (filter map? c))

(defn- replace-html [h c]
  (let [id (-> c :attrs :id)
        tag (:tag c)
        selector (keyword (str (name tag) "#" id))]
    (transform h [selector] (substitute c))))

(defn- wrap-html [h]
  (let [within (seq (select h [:_within]))]
    (if within
      (let [file (-> (first within) :attrs :file)
            outer (construct-html (html-resource file))
            content (maps (:content (first within)))]
        (loop [outer outer
               content (seq content)]
          (if content
            (recur (replace-html outer (first content)) (next content))
            outer)))
      h)))

(defn construct-html
  "Process a seq of Enlive nodes looking for `_include` and `_within` tags.
  Occurrences of `_include` are replaced by the resource to which they
  refer. The contents of `_within` tags are inserted into the resource
  to which they refer. `_within` is always the top-level tag in a file.
  `_include` can appear anywhere. Files with `_include` can reference
  files which themselves contain `_include` or `_within` tags, to an
  arbitrary level of nesting.

  For more information, see '[Design and Templating][dt]' in the project
  wiki.

  Returns a seq of Enlive nodes.

  [dt]: https://github.com/brentonashworth/one/wiki/Design-and-templating"
  [nodes]
  (wrap-html (include-html nodes)))

(defn load-html
  "Accept a file (a path to a resource on the classpath) and return a
  HTML string processed per construct-html."
  [file]
  (render (construct-html (html-resource file))))

;; Convert a snippent of HTML into a template function

(defn field-pairs [s]
  (partition 2 (for [pair (string/split s #",") x (string/split pair #":")] x)))

(defn field-map [coll]
  (reduce (fn [a [k v]] (assoc a v k))
          {}
          (mapcat field-pairs coll)))

(defn make-template [nodes field-value]
  (reduce (fn [a [k v]]
            (transform a [[(attr= :field field-value)]]
                       (if (= k "content")
                         (html-content (str "~{" v "}"))
                         (set-attr (keyword k) (str "~{" v "}")))))
          nodes
          (field-pairs field-value)))

(defn simplify-tseq
  "Concats strings together in templates to optimize them slightly"
  [s]
  (mapcat 
   #(if (string? (first %))
      [(apply str %)]
      %)
   (partition-by string? s)))

(defn tfn [nodes]
  (let [map-sym (gensym)
        field-nodes (-> nodes (select [(attr? :field)]))
        ts (map (fn [x] (-> x :attrs :field)) field-nodes)
        field-map (field-map ts)
        index (reduce (fn [a k]
                        (assoc a (str "~{" k "}") (list 'get map-sym (keyword k))))
                      {}
                      (keys field-map))
        nodes (reduce (fn [a field-value]
                        (make-template a field-value))
                      nodes
                      ts)
        nodes (-> nodes
                  (transform [(attr? :field)] (remove-attr :field))
                  (transform [(attr? :template)] (remove-attr :template)))
        seq (emit* nodes)
        seq (remove #(= (set %) #{\space \newline}) seq)
        seq (reduce (fn [a b]
                      (conj a (if (contains? index b)
                                (get index b)
                                b)))
                    []
                    seq)
        seq (simplify-tseq seq)]
    (list 'fn [map-sym]
          (cons 'str seq))))

(defn change-index [fields]
  (let [r-f (fn [acc item-vec] (let [k (first item-vec)
                                     v (second item-vec)]
                                 (update-in acc [k] (fn [x] (if x (conj x v) (list v))))))]
	  (reduce r-f {} (for [x fields y (field-pairs x)]
		                 [(keyword (second y)) {:field x    
                                            :type (if (= (first y) "content") :content :attr)
		                                        :attr-name (first y)}]))))

(defn make-dynamic-template [nodes key infos]
  (reduce (fn [a info]
            (transform a [[(attr= :field (:field info))]]
                       (set-attr :field (str (:field info) "," "id:" (:id info)))))
          nodes
          infos))

(defn dtfn [nodes static-fields]
  "Takes sequence of enlive nodes representing a template snippet and a set of static fields and returns a vector
   of two items - the first items is a map describing dynamic attributes of a template and the second item is a code for
   function which when called with map of static fields, returns a html string representing a given template filled with 
   values from static fields map (if there are any)."
  (let [map-sym (gensym)
        field-nodes (-> nodes (select [(attr? :field)]))
        ts (map (fn [x] (-> x :attrs :field)) field-nodes)
        ts-syms (reduce (fn [a x]
                          (assoc a x (gensym)))
                        {}
                        ts)
        change-index (reduce (fn [a [k v]]
                               (assoc a k (map (fn [info] (assoc info :id (get ts-syms (:field info)))) v)))
                             {}
                             (change-index ts))
        changes (reduce (fn [a [k v]]
                          (if (contains? static-fields k)
                            a
                            (assoc a k v)))
                        {}
                        change-index)
        nodes (reduce (fn [a [k info]]
                        (make-dynamic-template a key info))
                      nodes
                      changes)
        changes (reduce (fn [a [k v]]
                          (assoc a k (into [] (map (fn [info] (-> (if (= (:type info) :content) (dissoc info :attr-name) info)
                                                       (dissoc :field))) v))))
                        {}
                        changes)
        ids (set (mapcat (fn [[k v]] (map :id v)) changes))]
    (list 'fn [] (list 'let (vec (interleave ids (repeat (list 'gensym))))
           [changes (list 'fn [map-sym]
                          (list (tfn nodes)
                                (concat ['assoc map-sym]
                                        (interleave (map keyword ids)
                                                    ids))))]))))

(defn tnodes
  "Turns template defined in a file into sequence of enlive nodes. Takes two mandatory and one optional argument - the first 
   argument is the file name where template snippets are defined and the second argument is the name of a template snippet 
   inside a file. The optional argument is a collection of enlive selectors which should match the part(s) of a template 
   snippet we don't want to turn into enlive nodes - typical use case is when another inner template snippet(s) resides 
   inside a template snippet we want to turn into sequence of enlive nodes, which is the return value of this function."
  ([file name]
     (select (html-resource file) [(attr= :template name)]))
  ([file name empty]
     (reduce (fn [a b]
               (transform a b (html-content "")))
             (construct-html (tnodes file name))
             empty)))

(defn template-children [file name]
  (select (html-resource file) [(attr= :template name) :> :*]))
