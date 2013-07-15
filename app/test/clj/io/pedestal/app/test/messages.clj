; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.test.messages
  (:use [io.pedestal.app.messages :as msg :exclude (type)]
        clojure.test))

(deftest test-param-ns
  (let [param-ns @#'msg/param-ns]
    (is (= "io.pedestal.app.messages.param" param-ns))))

(deftest test-param
  (is (= :io.pedestal.app.messages.param/input (param :input)) "namespaces the passed keyword"))

(deftest test-add-message-type
  (is (= {topic :todo ::msg/type :clear-completed}
         (add-message-type :clear-completed {topic :todo}))
      "injects the transform-name into message as type")
  (is (= {topic :todo ::msg/type :my-other-type}
         (add-message-type :clear-completed {topic :todo ::msg/type :my-other-type}))
      "returns the original message if type is already present"))

(deftest test-fill-params
  (is (= [{topic :todo, ::msg/type :create-todo, :content "Get 'er done."}]
         (fill-params {(param :content) "Get 'er done."}
                      [{topic :todo, ::msg/type :create-todo, (param :content) {}}]))
      "returns the original message with params filled")
  (is (thrown-with-msg?
        AssertionError
        #"These keys are invalid: \[:bad-key\]"
        (fill-params {:bad-key :bad-value} [{}]))))

(deftest test-message-params
  (is (= [(param :foo), (msg/param :bar)]
         (message-params [{(param :foo) {}, :something :else}, {(param :bar) {}, (param :foo) {}}]))
      "collects all of the keys from messages that are params"))

(deftest test-keys-to-param-keys
  (is (= {(param :age) 42, (param :name) "Joe"}
         (keys-to-param-keys {:age 42, :name "Joe"}))
      "turns every key into param namespaced key"))

(deftest test-fill
  (let [person-id 9001]
    (is (= [{topic :person ::msg/type :set-age :age 42 :id person-id}]
           (fill :set-age
                 [{::msg/topic :person, :id person-id, (param :age) {}}]
                 {:age 42}))
        "fills message-topic and params when input-map is supplied")
    (is (= [{topic :todo ::msg/type :clear-completed}]
           (fill :clear-completed
                 [{::msg/topic :todo}]))
        "fills message-topic when no input-map is supplied")
    (is (thrown-with-msg? AssertionError
          #"Missing keys"
          (fill :set-age [{(param :age) {}}]))
        "fails an assertion when the input-map is missing param keys")))
