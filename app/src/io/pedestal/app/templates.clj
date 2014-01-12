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
  (:require [clojure.string :as string]
            [clojure.java.io :as io])
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

(defn- valid-tags [c] (filter (every-pred map? #(contains? % :tag) ) c))

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
            content (valid-tags (:content (first within)))]
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

(defn html-dependencies
  "Returns a seq of filenames referenced in _within or _include tags
starting with file, to an arbitrary level of nesting."
  [file]
  (letfn [(find-dep-files [nodes filenames]
          (if-let [dep-nodes (seq (concat (select nodes [:_include])
                                          (select nodes [:_within])))]
            (let [fs (remove nil? (map (comp :file :attrs) dep-nodes))]
              (recur (mapcat html-resource fs) (concat filenames fs)))
            filenames))]
    (find-dep-files (html-resource file) nil)))

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

(defn- field-information-pair
  "Takes a field name, target attribute and original field string.

   Returns a pair of name and map of information about field.

   Example:

   (field-information-pair \"name\" \"content\" \"content:name\")
   ; -> [:name {:field \"content:name\",
                :type :content
                :attr-name \"content\"}]"
  [name target field-string]
  [(keyword name) {:field field-string
                   :type (if (= target "content") :content :attr)
                   :attr-name target}])

(defn- gen-change-index
  "Takes a list of raw field attribute strings.

   Returns a map of field name keywords to a list of associated field
   information maps.

   Example:
   (gen-change-index [\"content:name\", \"value:name\"])
   ; -> {:name ({:field \"value:name\", :type :attr, :attr-name \"value\"}
                {:field \"content:name\", :type :content, :attr-name \"content\"})}"
  [fields]
  (reduce
   (fn [acc [k v]] (update-in acc [k] #(conj % v)))
   {}
   (for [field-string fields
         [target name] (field-pairs field-string)]
     (field-information-pair name target field-string))))

(defn- insert-ids
  "Takes a change-index and unique-symbols, a map of field-string to
   unique symbols.

   Returns a change-index where unique symbols from unique-symbols
   have been assoced into change-index's field-infos where
   field-info's :field value matches a key in unique-symbols"
   [change-index unique-symbols]
  (reduce (fn [a [k v]]
            (assoc a k (map (fn [field-info] (assoc field-info :id (get unique-symbols (:field field-info)))) v)))
          {}
          change-index))

(defn- remove-static-fields
  "Takes a change-index and a list of static-fields.

   Returns a new change-index with field matching static-fields
   removed."
  [change-index static-fields]
  (let [filtered-fields (set static-fields)]
    (into {} (remove (fn [[field _]] (contains? filtered-fields field))
                     change-index))))

(defn- append-field-ids
  "Takes enlive nodes and info maps containing :field and :id keys.

   Returns nodes where :id values from infos have been appended onto
   nodes field attributes (where :field values match the field attribute.)"
  [nodes infos static-fields]
  (reduce (fn [a info]
            (if-not (re-find (re-pattern (str "id:(" (string/join "|" (map name static-fields)) ")"))
                               (:field info))
              (transform a [[(attr= :field (:field info))]]
                         (set-attr :field (str (:field info) "," "id:" (:id info))))
              a))
          nodes
          infos))

(defn- simplify-info-maps
  "Simplify info-maps in change-index. Removes :attr-name (if :type
   is :content) and :field keys of each info-map in change index"
  [change-index]
  (letfn [(remove-attr-name [info-map]
            (if (= (:type info-map) :content)
              (dissoc info-map :attr-name)
              info-map))
          (remove-field [info-map]
            (dissoc info-map :field))
          (simplify-maps [info-maps]
            (mapv (comp remove-field remove-attr-name) info-maps))]
    (into {} (map (fn [[field-name info-maps]]
                    [field-name (simplify-maps info-maps)])
                  change-index))))

(defn dtfn
  "Takes sequence of enlive nodes representing a template snippet and
  a set of static fields (optional) and returns a vector of two items
  - the first items is a map describing dynamic attributes of a
  template and the second item is a code for function which when
  called with map of static fields, returns a html string representing
  a given template filled with values from static fields
  map (if there are any)."
  ([nodes] (dtfn nodes #{}))
  ([nodes static-fields]
   (let [map-sym (gensym)
         field-nodes (-> nodes (select [(attr? :field)]))
         ts (map (fn [x] (-> x :attrs :field)) field-nodes)
         ts-syms (reduce (fn [m x]
                           (assoc m
                                  (-> x :attrs :field)
                                  (symbol (or (-> x :attrs :id)
                                              (if-let [static-id (second (first (filter (every-pred #(= "id" (first %))
                                                                                                    #(contains? (set static-fields) (keyword (second %))))
                                                                                        (field-pairs (-> x :attrs :field)))))]
                                                (with-meta (symbol static-id) {:static-id true}))
                                              (gensym)))))
                         {}
                         field-nodes)
         changes (-> ts
                     gen-change-index
                     (insert-ids ts-syms)
                     (remove-static-fields static-fields))
         nodes (reduce (fn [a [_ info-map]]
                         (append-field-ids a info-map static-fields))
                       nodes
                       changes)
         changes (simplify-info-maps changes)
         ids (mapcat (fn [[_ field-infos]] (map :id field-infos))
                     changes)]
     (list 'fn [] (list 'let (vec (interleave ids (map str ids)))
                        [changes (list 'fn [map-sym]
                                       (list (tfn nodes)
                                             (let [generated-ids (remove #(:static-id (meta %)) ids)
                                                   id-map (interleave (map keyword generated-ids) generated-ids)]
                                               (if (seq id-map)
                                                 (concat ['assoc map-sym] id-map)
                                                 map-sym))))])))))

(defn tnodes
  "Turns template defined in a file into sequence of enlive nodes.
   Takes two mandatory and one optional argument - the first argument
   is the file name where template snippets are defined and the second
   argument is the name of a template snippet inside a file. The
   optional argument is a collection of enlive selectors which should
   match the part(s) of a template snippet we don't want to turn into
   enlive nodes - typical use case is when another inner template
   snippet(s) resides inside a template snippet we want to turn into
   sequence of enlive nodes, which is the return value of this
   function."
  ([file name]
     (select (html-resource file) [(attr= :template name)]))
  ([file name empty]
     (reduce (fn [a b]
               (transform a b (html-content "")))
             (construct-html (tnodes file name))
             empty)))

(defn template-children [file name]
  (select (html-resource file) [(attr= :template name) :> :*]))
