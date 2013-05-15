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
  (:use [io.pedestal.app.templates :as tmpl]
        clojure.test))

(deftest test-change-index
  (is (= {:id '({:field "id:id" :type :attr :attr-name "id"})
          :status '({:field "class:status" :type :attr :attr-name "class"})} 
         (tmpl/change-index '("id:id" "class:status"))) 
      "builds change index map out of single fields")
  (is (= {:id '({:field "id:id" :type :attr :attr-name "id"})
          :status '({:field "class:status,content:text" :type :attr :attr-name "class"})
          :text '({:field "class:status,content:text" :type :content :attr-name "content"})} 
         (tmpl/change-index '("id:id" "class:status,content:text"))) 
      "builds change index map out of single and plural field")
  (is (= {:id '({:field "id:id" :type :attr :attr-name "id"})
          :text '({:field "value:text" :type :attr :attr-name "value"}
                  {:field "content:text" :type :content :attr-name "content"})} 
         (tmpl/change-index '("id:id" "content:text" "value:text"))) 
      "builds change index map out of one normal and two overlapping (same data-field) single fields"))