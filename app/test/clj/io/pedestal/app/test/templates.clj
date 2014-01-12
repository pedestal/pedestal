; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.test.templates
  (:use [io.pedestal.app.templates]
        [net.cgrand.enlive-html :as enlive]
        clojure.test))

(deftest test-change-index
  (let [gen-change-index @#'io.pedestal.app.templates/gen-change-index]
    (is (= {:id '({:field "id:id" :type :attr :attr-name "id"})
            :status '({:field "class:status" :type :attr :attr-name "class"})}
           (gen-change-index '("id:id" "class:status")))
        "builds change index map out of single fields")
    (is (= {:id '({:field "id:id" :type :attr :attr-name "id"})
            :status '({:field "class:status,content:text" :type :attr :attr-name "class"})
            :text '({:field "class:status,content:text" :type :content :attr-name "content"})}
           (gen-change-index '("id:id" "class:status,content:text")))
        "builds change index map out of single and plural field")
    (is (= {:id '({:field "id:id" :type :attr :attr-name "id"})
            :text '({:field "value:text" :type :attr :attr-name "value"}
                    {:field "content:text" :type :content :attr-name "content"})}
           (gen-change-index '("id:id" "content:text" "value:text")))
        "builds change index map out of one normal and two overlapping (same data-field) single fields")))

(def dtfn-test-nodes
    (enlive/html-snippet "<li template='todo' field='id:line-item-id,accesskey:access-key'>
                           <div id='completed_123' field='class:completed'>
                            <div class='view'>
                              <input class='toggle' type='checkbox' checked>
                              <label field='content:text'>Create a TodoMVC template</label>
                              <button class='destroy'></button>
                            </div>
                            <input value='Create a TodoMVC template' field='value:text,class:id'>
                          </div>
                        </li>"))

(deftest test-simplify-info-maps
  (let [simplify-info-maps @#'io.pedestal.app.templates/simplify-info-maps
        change-index {:completed '({:id :foo, :field "class:completed", :type :attr, :attr-name "class"})
                      :text '({:id :baz, :field "value:text,class:id", :type :attr, :attr-name "value"}
                              {:id :bar, :field "content:text", :type :content, :attr-name "content"})
                      :id '({:id :baz, :field "value:text,class:id", :type :attr, :attr-name "class"})}]
    (is (= (simplify-info-maps change-index)
           {:id [{:id :baz, :type :attr, :attr-name "class"}]
            :text [{:id :baz, :type :attr, :attr-name "value"}
                   {:id :bar, :type :content}]
            :completed [{:id :foo, :type :attr, :attr-name "class"}]}))))

(defmacro template-macro [] (dtfn dtfn-test-nodes #{:line-item-id}))

(deftest test-dtfn
  (let [[dynamic-attributes html-fn] ((template-macro))
        values {:text "This is text", :completed "incomplete", :id "todo-1" :line-item-id "mycustomid"}
        result-html (html-fn values)
        result-nodes  (enlive/html-snippet result-html)]
    (is (= #{:id :completed :text :access-key} (set (keys dynamic-attributes))))
    (is (= 1 (count (:id dynamic-attributes))))
	  (is (= "completed_123" (-> (:completed dynamic-attributes) first :id))
	      "when id exists, it is preserved in id map")
	  (is (= "completed_123" (-> (enlive/select result-nodes [:li :div.incomplete]) first :attrs :id))
	      "when id exists, it is preserved in result-html")
	  (is (.startsWith (-> (:id dynamic-attributes) first :id str) "G__")
	      "when id does not exist, it is auto generated in id map")
	  (is (.startsWith (-> (enlive/select result-nodes [:li :div.incomplete :input.todo-1]) first :attrs :id str) "G__")
	      "when id does not exist, id is auto generated in result-html")
    (is (= 1 (count (:completed dynamic-attributes))))
    (is (= 2 (count (:text dynamic-attributes))))
    (is (= 1 (count (:access-key dynamic-attributes))))
    (is (= "line-item-id" (-> dynamic-attributes :access-key first :id))
        "The :id for a dynamic attribute info map on a node whose template also contained an id field pair should be the id attribute key")
    (is (let [info-map (-> dynamic-attributes :id first)]
          (and (= (:attr-name info-map) "class")
               (= (:type info-map) :attr))))
    (is (let [info-map (-> dynamic-attributes :completed first)]
          (and (= (:attr-name info-map) "class")
               (= (:type info-map) :attr))))
    (is (let [info-map (-> dynamic-attributes :text first)]
          (and (= (:attr-name info-map) "value")
               (= (:type info-map) :attr))))
    (is (let [info-map (-> dynamic-attributes :text second)]
          (and (= (:attr-name info-map) nil)
               (= (:type info-map) :content))))
    (is (let [info-map (-> dynamic-attributes :access-key first)]
          (and (= (:attr-name info-map) "accesskey")
               (= (:type info-map) :attr))))
    (is (= 1 (count (enlive/select result-nodes [:.todo-1])))
         "there is one element with class todo-1")
    (is (= 1 (count (enlive/select result-nodes [:li :div.incomplete])))
        "there is one div with class incomplete")
    (is (->> (enlive/select result-nodes [:label])
             (some #(= (:content %) ["This is text"])))
        "there is a label with content 'This is text'")
    (is (= "mycustomid" (->> (enlive/select result-nodes [:li])
                             first
                             :attrs
                             :id))
        "a static id can be set on a node containing dynamic fields")
    (is (= 1 (count (enlive/select result-nodes [(enlive/attr= :value "This is text")])))
        "there is one input with value 'This is text'")))

(deftest test-replace-template
  (let [replaced (load-html (clojure.java.io/file "test/clj/template.html"))
        nodes (html-parse replaced)]
    (is (= (count (enlive/select nodes [:div.container :div#content])) 1))))
