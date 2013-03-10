;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.app.test.tree
  (:require [io.pedestal.app.messages :as msg])
  (:use io.pedestal.app.tree
        [io.pedestal.app.query :only (q)]
        clojure.test))

(defn test-apply-deltas [ui d]
  (into {} (apply-deltas ui d)))

(defn isomorphic [ui d]
  (into {} (-> ui
               (apply-deltas d)
               (apply-deltas (invert d)))))

(deftest test-real-path
  (let [real-path #'io.pedestal.app.tree/real-path]
    (is (= (real-path []) [:tree]))
    (is (= (real-path [:a]) [:tree :children :a]))
    (is (= (real-path [:a :b]) [:tree :children :a :children :b]))
    (is (= (real-path [:a :b 2]) [:tree :children :a :children :b :children 2]))))

(deftest test-node-enter
  (testing "add a single node"
    (let [ui new-app-model
          deltas [[:node-create [] :map]]]
      (is (= (test-apply-deltas ui deltas)
             {:deltas {0 [{:delta [:node-create [] :map] :seq 0 :t 0}]}
              :this-tx []
              :tree {:children {}}
              :seq 1
              :t 1}))))
  (testing "add a single node without a child type"
    (let [ui new-app-model
          deltas [[:node-create []]]]
      (is (= (test-apply-deltas ui deltas)
             {:deltas {0 [{:delta [:node-create [] :map] :seq 0 :t 0}]}
              :this-tx []
              :tree {:children {}}
              :seq 1
              :t 1}))))
  (testing "build a tree"
    (let [expected-tree {:deltas {0 [{:delta [:node-create [] :map] :seq 0 :t 0}
                                     {:delta [:node-create [:a] :map] :seq 1 :t 0}
                                     {:delta [:node-create [:a :b] :vector] :seq 2 :t 0}
                                     {:delta [:node-create [:a :b 0] :map] :seq 3 :t 0}]}
                         :this-tx []
                         :tree {:children {:a {:children {:b {:children [{:children {}}]}}}}}
                         :seq 4
                         :t 1}]
      (testing "by explicitly describing the change"
        (let [ui new-app-model
              deltas [[:node-create [] :map]
                      [:node-create [:a] :map]
                      [:node-create [:a :b] :vector]
                      [:node-create [:a :b 0] :map]]]
          (is (= (test-apply-deltas ui deltas) expected-tree))))
      (testing "by partially describing the change"
        (let [ui new-app-model
              deltas [[:node-create [:a :b 0] :map]]]
          (is (= (test-apply-deltas ui deltas) expected-tree))))
      (testing "by applying a predefined set of changes - node-enter is idempotent"
        (let [ui new-app-model
              deltas [[:node-create [] :map]
                      [:node-create [:a] :map]
                      [:node-create [:a :b] :vector]
                      [:node-create [:a] :map]
                      [:node-create [:a :b] :vector]
                      [:node-create [:a :b 0] :map]]]
          (is (= (test-apply-deltas ui deltas) expected-tree))))
      (testing "by explicit example"
        (let [ui new-app-model
              deltas [{:children
                       {:a {:children
                            {:b {:children
                                 [{}]}}}}}]]
          (is (= (test-apply-deltas ui deltas) expected-tree))))
      (testing "by compact example"
        (let [ui new-app-model
              deltas [{:a {:b [{}]}}]]
          (is (= (test-apply-deltas ui deltas) expected-tree))))
      (testing "with a path prefix"
        (let [ui new-app-model
              deltas [[:node-create [:a] {:b [{}]}]]]
          (is (= (test-apply-deltas ui deltas) expected-tree))))
      (testing " - can't change the type of node"
        (let [ui new-app-model
              deltas [[:node-create [] :map]
                      [:node-create [:a] :map]
                      [:node-create [:a :b] :vector]
                      [:node-create [:a] :map]
                      [:node-create [:a :b] :map]]]
          (is (thrown-with-msg? AssertionError
                #"The node at \[:a :b\] exists and is*"
                (test-apply-deltas ui deltas))))))))

(deftest test-node-exit
  (let [ui new-app-model
        deltas [{:a {:value 42
                     :attrs {:color :red :size 10}
                     :transforms {:x [{:y :z}]}
                     :children {:b
                                {:c
                                 [{:value 2
                                   :transforms {:f [{:x :p}]}}
                                  {:value 3
                                   :attrs {:color :blue}}]}}}}]
        start (test-apply-deltas ui deltas)
        delta-one {0 [{:delta [:node-create [] :map] :seq 0 :t 0}
                      {:delta [:node-create [:a] :map] :seq 1 :t 0}
                      {:delta [:value [:a] nil 42] :seq 2 :t 0}
                      {:delta [:attr [:a] :color nil :red] :seq 3 :t 0}
                      {:delta [:attr [:a] :size nil 10] :seq 4 :t 0}
                      {:delta [:transform-enable [:a] :x [{:y :z}]] :seq 5 :t 0}
                      {:delta [:node-create [:a :b] :map] :seq 6 :t 0}
                      {:delta [:node-create [:a :b :c] :vector] :seq 7 :t 0}
                      {:delta [:node-create [:a :b :c 0] :map] :seq 8 :t 0}
                      {:delta [:value [:a :b :c 0] nil 2] :seq 9 :t 0}
                      {:delta [:transform-enable [:a :b :c 0] :f [{:x :p}]] :seq 10 :t 0}
                      {:delta [:node-create [:a :b :c 1] :map] :seq 11 :t 0}
                      {:delta [:value [:a :b :c 1] nil 3] :seq 12 :t 0}
                      {:delta [:attr [:a :b :c 1] :color nil :blue] :seq 13 :t 0}]}]
    (testing "remove a leaf node with a value"
      (let [deltas [[:node-destroy [:a :b :c 1]]]]
        (is (= (test-apply-deltas start deltas)
               {:deltas (assoc delta-one 1
                               [{:delta [:value [:a :b :c 1] 3 nil] :seq 14 :t 1}
                                {:delta [:attr [:a :b :c 1] :color :blue nil] :seq 15 :t 1}
                                {:delta [:node-destroy [:a :b :c 1] :map] :seq 16 :t 1}])
                :this-tx []
                :tree {:children
                       {:a {:attrs {:color :red :size 10}
                            :value 42
                            :transforms {:x [{:y :z}]}
                            :children
                            {:b {:children
                                 {:c {:children [{:value 2
                                                  :transforms {:f [{:x :p}]}
                                                  :children {}}]}}}}}}}
                :seq 17
                :t 2}))))
    (testing "explicitly remove bottom of tree"
      (let [deltas [[:value [:a :b :c 1] nil]
                    [:node-destroy [:a :b :c 1]]
                    [:value [:a :b :c 0] nil]
                    [:transform-disable [:a :b :c 0] :f]
                    [:node-destroy [:a :b :c 0]]
                    [:node-destroy [:a :b :c]]]]
        (is (= (test-apply-deltas start deltas)
               {:deltas (assoc delta-one 1
                               [{:delta [:value [:a :b :c 1] 3 nil] :seq 14 :t 1}
                                {:delta [:attr [:a :b :c 1] :color :blue nil] :seq 15 :t 1}
                                {:delta [:node-destroy [:a :b :c 1] :map] :seq 16 :t 1}
                                {:delta [:value [:a :b :c 0] 2 nil] :seq 17 :t 1}
                                {:delta [:transform-disable [:a :b :c 0] :f [{:x :p}]] :seq 18 :t 1}
                                {:delta [:node-destroy [:a :b :c 0] :map] :seq 19 :t 1}
                                {:delta [:node-destroy [:a :b :c] :vector] :seq 20 :t 1}])
                :this-tx []
                :tree {:children
                       {:a {:attrs {:color :red :size 10}
                            :value 42
                            :transforms {:x [{:y :z}]}
                            :children
                            {:b {:children {}}}}}}
                :seq 21
                :t 2}))))
    (testing "implicitly remove bottom of tree"
      (let [deltas [[:node-destroy [:a :b :c]]]]
        (is (= (test-apply-deltas start deltas)
               {:deltas (assoc delta-one 1
                               [{:delta [:value [:a :b :c 1] 3 nil] :seq 14 :t 1}
                                {:delta [:attr [:a :b :c 1] :color :blue nil] :seq 15 :t 1}
                                {:delta [:node-destroy [:a :b :c 1] :map] :seq 16 :t 1}
                                {:delta [:value [:a :b :c 0] 2 nil] :seq 17 :t 1}
                                {:delta [:transform-disable [:a :b :c 0] :f [{:x :p}]] :seq 18 :t 1}
                                {:delta [:node-destroy [:a :b :c 0] :map] :seq 19 :t 1}
                                {:delta [:node-destroy [:a :b :c] :vector] :seq 20 :t 1}])
                :this-tx []
                :tree {:children
                       {:a {:attrs {:color :red :size 10}
                            :value 42
                            :transforms {:x [{:y :z}]}
                            :children
                            {:b {:children {}}}}}}
                :seq 21
                :t 2}))))
    (testing "implicitly remove the whole tree"
      (let [deltas [[:node-destroy []]]]
        (is (= (test-apply-deltas start deltas)
               {:deltas (assoc delta-one 1
                               [{:delta [:value [:a :b :c 1] 3 nil] :seq 14 :t 1}
                                {:delta [:attr [:a :b :c 1] :color :blue nil] :seq 15 :t 1}
                                {:delta [:node-destroy [:a :b :c 1] :map] :seq 16 :t 1}
                                {:delta [:value [:a :b :c 0] 2 nil] :seq 17 :t 1}
                                {:delta [:transform-disable [:a :b :c 0] :f [{:x :p}]] :seq 18 :t 1}
                                {:delta [:node-destroy [:a :b :c 0] :map] :seq 19 :t 1}
                                {:delta [:node-destroy [:a :b :c] :vector] :seq 20 :t 1}
                                {:delta [:node-destroy [:a :b] :map] :seq 21 :t 1}
                                {:delta [:value [:a] 42 nil] :seq 22 :t 1}
                                {:delta [:transform-disable [:a] :x [{:y :z}]] :seq 23 :t 1}
                                {:delta [:attr [:a] :size 10 nil] :seq 24 :t 1}
                                {:delta [:attr [:a] :color :red nil] :seq 25 :t 1}
                                {:delta [:node-destroy [:a] :map] :seq 26 :t 1}
                                {:delta [:node-destroy [] :map] :seq 27 :t 1}])
                :this-tx []
                :tree nil
                :seq 28
                :t 2}))))))

(deftest test-node-isomorphism
  (let [ui new-app-model
        deltas [[:node-create [] :map]
                [:node-create [:a] :map]
                [:node-create [:a :b] :vector]
                [:node-create [:a :b 0] :map]]
        inverse (map inverse (reverse deltas))]
    (is (= inverse
           [[:node-destroy [:a :b 0] :map]
            [:node-destroy [:a :b] :vector]
            [:node-destroy [:a] :map]
            [:node-destroy [] :map]]))
    (is (= (:tree ui) (:tree (isomorphic ui deltas))))))

(deftest test-map->deltas
  (let [map->deltas #'io.pedestal.app.tree/map->deltas]
    (is (= (map->deltas {:children
                         {:a {:value "Hello"
                              :children
                              {:b {:children {}}}}}}
                        [])
           [[:node-create [] :map]
            [:node-create [:a] :map]
            [:value [:a] "Hello"]
            [:node-create [:a :b] :map]]))
    (is (= (map->deltas {:a {:value "Hello"
                             :children
                             {:b {}}}}
                        [])
           [[:node-create [] :map]
            [:node-create [:a] :map]
            [:value [:a] "Hello"]
            [:node-create [:a :b] :map]]))
    (is (= (map->deltas {:children
                         {:a {:children
                              {:b {:children [{:children {}}]}}}}}
                        [])
           [[:node-create [] :map]
            [:node-create [:a] :map]
            [:node-create [:a :b] :vector]
            [:node-create [:a :b 0] :map]]))
    (is (= (map->deltas {:a {:b [{}]}} [])
           [[:node-create [] :map]
            [:node-create [:a] :map]
            [:node-create [:a :b] :vector]
            [:node-create [:a :b 0] :map]]))
    (is (= (map->deltas {:children
                         {:a {:value "Hello"
                              :attrs {:color :red}
                              :transforms {:x [{:y :z}]}
                              :children
                              {:b {:children {}}}}}}
                        [])
           [[:node-create [] :map]
            [:node-create [:a] :map]
            [:value [:a] "Hello"]
            [:attr [:a] :color :red]
            [:transform-enable [:a] :x [{:y :z}]]
            [:node-create [:a :b] :map]]))
    (is (= (map->deltas {:a {:value "Hello"
                             :attrs {:color :red}
                             :transforms {:x [{:y :z}]}
                             :children
                             {:b {}}}}
                        [])
           [[:node-create [] :map]
            [:node-create [:a] :map]
            [:value [:a] "Hello"]
            [:attr [:a] :color :red]
            [:transform-enable [:a] :x [{:y :z}]]
            [:node-create [:a :b] :map]]))
    (is (= (map->deltas {} [])
           [[:node-create [] :map]]))
    (is (= (map->deltas [] [])
           [[:node-create [] :vector]]))
    (is (= (map->deltas [{}] [])
           [[:node-create [] :vector]
            [:node-create [0] :map]]))
    (is (= (map->deltas {:a {:b [{:value 2}]}} [])
           [[:node-create [] :map]
            [:node-create [:a] :map]
            [:node-create [:a :b] :vector]
            [:node-create [:a :b 0] :map]
            [:value [:a :b 0] 2]]))
    (is (= (map->deltas {:a {:value 42
                             :attrs {:color :red :size 10}
                             :transforms {:x [{:y :z}]}
                             :children {:b
                                        {:c
                                         [{:value 2
                                           :transforms {:f [{:x :p}]}}
                                          {:value 3
                                           :attrs {:color :blue}}]}}}}
                        [])
           [[:node-create [] :map]
            [:node-create [:a] :map]
            [:value [:a] 42]
            [:attr [:a] :color :red]
            [:attr [:a] :size 10]
            [:transform-enable [:a] :x [{:y :z}]]
            [:node-create [:a :b] :map]
            [:node-create [:a :b :c] :vector]
            [:node-create [:a :b :c 0] :map]
            [:value [:a :b :c 0] 2]
            [:transform-enable [:a :b :c 0] :f [{:x :p}]]
            [:node-create [:a :b :c 1] :map]
            [:value [:a :b :c 1] 3]
            [:attr [:a :b :c 1] :color :blue]]))
    (testing "with path prefix"
      (is (= (map->deltas [{:value 2}]
                          [:a :b])
             [[:node-create [:a :b] :vector]
              [:node-create [:a :b 0] :map]
              [:value [:a :b 0] 2]])))))

(deftest test-remove-index-from-vector
  (let [remove-index-from-vector #'io.pedestal.app.tree/remove-index-from-vector]
    (is (= (remove-index-from-vector [0 1 2 3] 0)
           [1 2 3]))
    (is (= (remove-index-from-vector [0 1 2 3] 1)
           [0 2 3]))
    (is (= (remove-index-from-vector [0 1 2 3] 2)
           [0 1 3]))
    (is (= (remove-index-from-vector [0 1 2 3] 3)
           [0 1 2]))))

(deftest test-value-enter
  (let [ui new-app-model
        deltas [{:a {:b [{} {}]}}]
        start (test-apply-deltas ui deltas)]
    (let [deltas [[:value [:a :b] 42]
                  [:value [:a :b 1] 10]
                  [:value [:a] {:x {:y 5}}]
                  [:value [:a :b 1] 10]]] ;; don't record this
      (is (= (test-apply-deltas start deltas)
             {:deltas {0 [{:delta [:node-create [] :map] :seq 0 :t 0}
                          {:delta [:node-create [:a] :map] :seq 1 :t 0}
                          {:delta [:node-create [:a :b] :vector] :seq 2 :t 0}
                          {:delta [:node-create [:a :b 0] :map] :seq 3 :t 0}
                          {:delta [:node-create [:a :b 1] :map] :seq 4 :t 0}]
                       1 [{:delta [:value [:a :b] nil 42] :seq 5 :t 1}
                          {:delta [:value [:a :b 1] nil 10] :seq 6 :t 1}
                          {:delta [:value [:a ] nil {:x {:y 5}}] :seq 7 :t 1}]}
              :this-tx []
              :tree {:children {:a {:value {:x {:y 5}}
                                    :children {:b {:value 42
                                                   :children [{:children {}}
                                                              {:value 10
                                                               :children {}}]}}}}}
              :seq 8
              :t 2})))))

(deftest test-value-exit
  (let [ui new-app-model
        deltas [{:a {:value {:x {:y 5}}
                     :children
                     {:b {:value 42
                          :children
                          [{}
                           {:value 10}]}}}}]
        start (test-apply-deltas ui deltas)]
    (let [deltas [[:value [:a :b] nil]
                  [:value [:a :b 1] nil]
                  [:value [:a :b] nil] ;; don't record this
                  [:value [:a] {:x {:y 5}} nil]]] 
      (is (= (test-apply-deltas start deltas)
             {:deltas {0 [{:delta [:node-create [] :map] :seq 0 :t 0}
                          {:delta [:node-create [:a] :map] :seq 1 :t 0}
                          {:delta [:value [:a] nil {:x {:y 5}}] :seq 2 :t 0}
                          {:delta [:node-create [:a :b] :vector] :seq 3 :t 0}
                          {:delta [:value [:a :b] nil 42] :seq 4 :t 0}
                          {:delta [:node-create [:a :b 0] :map] :seq 5 :t 0}
                          {:delta [:node-create [:a :b 1] :map] :seq 6 :t 0}
                          {:delta [:value [:a :b 1] nil 10] :seq 7 :t 0}]
                       1 [{:delta [:value [:a :b] 42 nil] :seq 8 :t 1}
                          {:delta [:value [:a :b 1] 10 nil] :seq 9 :t 1}
                          {:delta [:value [:a] {:x {:y 5}} nil] :seq 10 :t 1}]}
              :this-tx []
              :tree {:children {:a {:children {:b {:children [{:children {}}
                                                              {:children {}}]}}}}}
              :seq 11
              :t 2})))
    (let [deltas [[:value [:a :b 1] 11 nil]]]
      (is (thrown-with-msg? AssertionError
            #"The old value at path \[:a :b 1\] is 10 but was expected to be 11."
            (test-apply-deltas start deltas))))))

(deftest test-value-isomorphism
  (let [ui new-app-model
        deltas [{:a {:b [{} {}]}}]
        start (test-apply-deltas ui deltas)]
    (let [deltas [[:value [:a :b] nil 42]
                  [:value [:a :b 1] nil 10]
                  [:value [:a] nil {:x {:y 5}}]]]
      (is (= (:tree start) (:tree (isomorphic start deltas)))))))

(deftest test-value-update
  (let [ui new-app-model
        deltas [{:a {:value {:x {:y 5}}
                     :children
                     {:b {:value 42
                          :children
                          [{}
                           {:value 10}]}}}}]
        start (test-apply-deltas ui deltas)]
    (let [deltas [[:value [:a :b] 43]
                  [:value [:a :b 1] 100]
                  [:value [:a :b] 43]]] ;; don't record this delta
      (is (= (test-apply-deltas start deltas)
             {:deltas {0 [{:delta [:node-create [] :map] :seq 0 :t 0}
                          {:delta [:node-create [:a] :map] :seq 1 :t 0}
                          {:delta [:value [:a] nil {:x {:y 5}}] :seq 2 :t 0}
                          {:delta [:node-create [:a :b] :vector] :seq 3 :t 0}
                          {:delta [:value [:a :b] nil 42] :seq 4 :t 0}
                          {:delta [:node-create [:a :b 0] :map] :seq 5 :t 0}
                          {:delta [:node-create [:a :b 1] :map] :seq 6 :t 0}
                          {:delta [:value [:a :b 1] nil 10] :seq 7 :t 0}]
                       1 [{:delta [:value [:a :b] 42 43] :seq 8 :t 1}
                          {:delta [:value [:a :b 1] 10 100] :seq 9 :t 1}]}
              :this-tx []
              :tree {:children {:a {:value {:x {:y 5}}
                                    :children {:b {:value 43
                                                   :children [{:children {}}
                                                              {:value 100
                                                               :children {}}]}}}}}
              :seq 10
              :t 2})))))

(deftest test-value-update-isomorphism
  (let [ui new-app-model
        deltas [{:a {:value {:x {:y 5}}
                     :children
                     {:b {:value 42
                          :children
                          [{}
                           {:value 10}]}}}}]
        start (test-apply-deltas ui deltas)]
    (let [deltas [[:value [:a :b] 42 43]
                  [:value [:a :b 1] 10 100]]]
      (is (= (:tree start) (:tree (isomorphic start deltas)))))))

(deftest test-attr
  (let [ui new-app-model
        deltas [[:node-create [] :map]
                [:node-create [:a] :map]
                [:node-create [:a :b] :vector]
                [:node-create [:a :b 0] :map]]
        start (test-apply-deltas ui deltas)]
    (let [deltas [[:attr [:a :b] :color :red]
                  [:attr [:a :b 0] :color :blue]]]
      (is (= (test-apply-deltas start deltas)
             {:deltas {0 [{:delta [:node-create [] :map] :seq 0 :t 0}
                          {:delta [:node-create [:a] :map] :seq 1 :t 0}
                          {:delta [:node-create [:a :b] :vector] :seq 2 :t 0}
                          {:delta [:node-create [:a :b 0] :map] :seq 3 :t 0}]
                       1 [{:delta [:attr [:a :b] :color nil :red] :seq 4 :t 1}
                          {:delta [:attr [:a :b 0] :color nil :blue] :seq 5 :t 1}]}
              :this-tx []
              :tree {:children {:a {:children {:b {:attrs {:color :red}
                                                   :children [{:attrs {:color :blue}
                                                               :children {}}]}}}}}
              :seq 6
              :t 2})))))

(deftest test-attr-isomorphism
  (let [ui new-app-model
        deltas [[:node-create [] :map]
                [:node-create [:a] :map]
                [:node-create [:a :b] :vector]
                [:node-create [:a :b 0] :map]]
        start (test-apply-deltas ui deltas)]
    (let [deltas [[:attr [:a :b] :color nil :red]
                  [:attr [:a :b 0] :color nil :blue]]]
      (is (= (:tree start) (:tree (isomorphic start deltas)))))))

(deftest test-transform-enter
  (let [ui new-app-model
        deltas [[:node-create [] :map]
                [:node-create [:a] :map]
                [:node-create [:a :b] :vector]
                [:node-create [:a :b 0] :map]]
        start (test-apply-deltas ui deltas)]
    (let [deltas [[:transform-enable [:a :b] :x [{:y :z}]]
                  [:transform-enable [:a :b] :m [{:p :t}]]
                  [:transform-enable [:a :b] :x [{:y :z}]]]]
      ;; ignore duplicate :transform-enables
      (is (= (test-apply-deltas start deltas)
             {:deltas {0 [{:delta [:node-create [] :map] :seq 0 :t 0}
                          {:delta [:node-create [:a] :map] :seq 1 :t 0}
                          {:delta [:node-create [:a :b] :vector] :seq 2 :t 0}
                          {:delta [:node-create [:a :b 0] :map] :seq 3 :t 0}]
                       1 [{:delta [:transform-enable [:a :b] :x [{:y :z}]] :seq 4 :t 1}
                          {:delta [:transform-enable [:a :b] :m [{:p :t}]] :seq 5 :t 1}]}
              :this-tx []
              :tree {:children {:a {:children {:b {:transforms {:x [{:y :z}]
                                                            :m [{:p :t}]}
                                                   :children [{:children {}}]}}}}}
              :seq 6
              :t 2})))
    (let [deltas [[:transform-enable [:a :b] :x [{:y :z}]]
                  [:transform-enable [:a :b] :x [{:j :k}]]]]
      (is (thrown-with-msg? AssertionError
            #"A different transform :x at path \[:a :b\] already exists."
            (test-apply-deltas start deltas))))))

(deftest test-transform-exit
  (let [ui new-app-model
        deltas [[:node-create [] :map]
                [:node-create [:a] :map]
                [:node-create [:a :b] :vector]
                [:node-create [:a :b 0] :map]
                [:transform-enable [:a :b] :x [{:y :z}]]
                [:transform-enable [:a :b] :m [{:p :t}]]]
        start (test-apply-deltas ui deltas)]
    (let [deltas [[:transform-disable [:a :b] :x]
                  [:transform-disable [:a :b] :x]]] ;; ignore
      (is (= (test-apply-deltas start deltas)
             {:deltas {0 [{:delta [:node-create [] :map] :seq 0 :t 0}
                          {:delta [:node-create [:a] :map] :seq 1 :t 0}
                          {:delta [:node-create [:a :b] :vector] :seq 2 :t 0}
                          {:delta [:node-create [:a :b 0] :map] :seq 3 :t 0}
                          {:delta [:transform-enable [:a :b] :x [{:y :z}]] :seq 4 :t 0}
                          {:delta [:transform-enable [:a :b] :m [{:p :t}]] :seq 5 :t 0}]
                       1 [{:delta [:transform-disable [:a :b] :x [{:y :z}]] :seq 6 :t 1}]}
              :this-tx []
              :tree {:children {:a {:children {:b {:transforms {:m [{:p :t}]}
                                                   :children [{:children {}}]}}}}}
              :seq 7
              :t 2})))))

(deftest test-transform-isomorphism
  (let [ui new-app-model
        deltas [[:node-create [] :map]
                [:node-create [:a] :map]
                [:node-create [:a :b] :vector]
                [:node-create [:a :b 0] :map]]
        start (test-apply-deltas ui deltas)]
    (let [deltas [[:transform-enable [:a :b] :x [{:y :z}]]
                  [:transform-enable [:a :b] :m [{:p :t}]]]]
      (is (= (:tree start) (:tree (isomorphic start deltas)))))))

(deftest test-since-t
  (let [deltas {0 [{:delta [:node-create [] :map] :seq 0 :t 0}
                   {:delta [:node-create [:a] :map] :seq 1 :t 0}]
                1 [{:delta [:node-create [:a :b] :vector] :seq 2 :t 1}]
                2 [{:delta [:node-create [:a :b 0] :map] :seq 3 :t 2}]
                3 [{:delta [:transform-enable [:a :b] :x [{:y :z}]] :seq 4 :t 3}
                   {:delta [:transform-enable [:a :b] :m [{:p :t}]] :seq 5 :t 3}]
                4 [{:delta [:transform-disable [:a :b] :x [{:y :z}]] :seq 6 :t 4}]}]
    (is (= (since-t {:deltas deltas :t 5} 0)
           (map :delta (apply concat (vals deltas)))))
    (is (= (count (since-t {:deltas deltas :t 5} 1))
           5))
    (is (= (count (since-t {:deltas deltas :t 5} 3))
           3))))

(deftest test-children-exit
  (let [ui new-app-model
        deltas [{:a {:b [{} {}] :c {:x {} :y {}}}}]
        start (test-apply-deltas ui deltas)
        delta-one {0 [{:delta [:node-create [] :map] :seq 0 :t 0}
                      {:delta [:node-create [:a] :map] :seq 1 :t 0}
                      {:delta [:node-create [:a :b] :vector] :seq 2 :t 0}
                      {:delta [:node-create [:a :b 0] :map] :seq 3 :t 0}
                      {:delta [:node-create [:a :b 1] :map] :seq 4 :t 0}
                      {:delta [:node-create [:a :c] :map] :seq 5 :t 0}
                      {:delta [:node-create [:a :c :x] :map] :seq 6 :t 0}
                      {:delta [:node-create [:a :c :y] :map] :seq 7 :t 0}]}]
    (let [deltas [[:children-exit [:a :b]]]]
      (is (= (test-apply-deltas start deltas)
             {:deltas (assoc delta-one 1
                             [{:delta [:node-destroy [:a :b 1] :map] :seq 8 :t 1}
                              {:delta [:node-destroy [:a :b 0] :map] :seq 9 :t 1}])
              :this-tx []
              :tree {:children {:a {:children {:b {:children []}
                                               :c {:children {:x {:children {}}
                                                              :y {:children {}}}}}}}}
              :seq 10
              :t 2})))
    (let [deltas [[:children-exit [:a :c]]]
          test-tree (test-apply-deltas start deltas)]
      (is (= (:seq test-tree) 10))
      (is (= (:t test-tree) 2))
      (is (= (:tree test-tree)
             {:children {:a {:children {:b {:children [{:children {}}
                                                       {:children {}}]}
                                        :c {:children {}}}}}}))
      (is (= (set (since-t test-tree 0))
             (set (map :delta
                       (apply concat
                              (vals (assoc delta-one 1
                                           [{:delta [:node-destroy [:a :c :x] :map] :t 1}
                                            {:delta [:node-destroy [:a :c :y] :map] :t 1}]))))))))
    (let [deltas [[:children-exit [:a]]]
          test-tree (test-apply-deltas start deltas)]
      (is (= (:seq test-tree) 14))
      (is (= (:t test-tree) 2))
      (is (= (:tree test-tree)
             {:children {:a {:children {}}}}))
      (is (= (set (since-t test-tree 0))
             (set (map :delta
                       (apply concat
                              (vals (assoc delta-one 1
                                           [{:delta [:node-destroy [:a :b 1] :map] :t 1}
                                            {:delta [:node-destroy [:a :b 0] :map] :t 1}
                                            {:delta [:node-destroy [:a :c :x] :map] :t 1}
                                            {:delta [:node-destroy [:a :c :y] :map] :t 1}
                                            {:delta [:node-destroy [:a :b] :vector] :t 1}
                                            {:delta [:node-destroy [:a :c] :map] :t 1}]))))))))))

(deftest test-build-by-example
  (testing "build a tree by example"
    (let [ui new-app-model
          deltas [{:a {}}]]
      (is (= (test-apply-deltas ui deltas)
             {:deltas {0 [{:delta [:node-create [] :map] :seq 0 :t 0}
                          {:delta [:node-create [:a] :map] :seq 1 :t 0}]}
              :this-tx []
              :tree {:children {:a {:children {}}}}
              :seq 2
              :t 1})))
    (let [ui new-app-model
          deltas [{:a {:value 42
                       :attrs {:color :red :size 10}
                       :transforms {:x [{:y :z}]}
                       :children {:b
                                  {:c
                                   [{:value 2
                                     :transforms {:f [{:x :p}]}}
                                    {:value 3
                                     :attrs {:color :blue}}]}}}}]]
      (is (= (test-apply-deltas ui deltas)
             {:deltas {0 [{:delta [:node-create [] :map] :seq 0 :t 0}
                          {:delta [:node-create [:a] :map] :seq 1 :t 0}
                          {:delta [:value [:a] nil 42] :seq 2 :t 0}
                          {:delta [:attr [:a] :color nil :red] :seq 3 :t 0}
                          {:delta [:attr [:a] :size nil 10] :seq 4 :t 0}
                          {:delta [:transform-enable [:a] :x [{:y :z}]] :seq 5 :t 0}
                          {:delta [:node-create [:a :b] :map] :seq 6 :t 0}
                          {:delta [:node-create [:a :b :c] :vector] :seq 7 :t 0}
                          {:delta [:node-create [:a :b :c 0] :map] :seq 8 :t 0}
                          {:delta [:value [:a :b :c 0] nil 2] :seq 9 :t 0}
                          {:delta [:transform-enable [:a :b :c 0] :f [{:x :p}]] :seq 10 :t 0}
                          {:delta [:node-create [:a :b :c 1] :map] :seq 11 :t 0}
                          {:delta [:value [:a :b :c 1] nil 3] :seq 12 :t 0}
                          {:delta [:attr [:a :b :c 1] :color nil :blue] :seq 13 :t 0}]}
              :this-tx []
              :tree {:children
                     {:a {:attrs {:color :red :size 10}
                          :value 42
                          :transforms {:x [{:y :z}]}
                          :children
                          {:b {:children
                               {:c {:children [{:value 2
                                                :transforms {:f [{:x :p}]}
                                                :children {}}
                                               {:attrs {:color :blue}
                                                :value 3
                                                :children {}}]}}}}}}}
              :seq 14
              :t 1})))))

;; Query Tests
;; ================================================================================

(deftest test-transform->entities
  (let [next-eid-atom @#'io.pedestal.app.tree/next-eid-atom
        transform->entities #'io.pedestal.app.tree/transform->entities]
    (reset! next-eid-atom 10)
    (is (= (transform->entities :navigate
                            [{:page :page/configuration}
                             {msg/topic :y :style :awesome}]
                            1)
           [{:t/transform-name :navigate :t/id 11 :t/node 1 :t/type :t/transform}
            {:page :page/configuration :t/transform 11 :t/id 12 :t/type :t/message}
            {:style :awesome :t/transform 11 :t/id 13 :t/type :t/message msg/topic :y}]))))

(deftest test-transforms->entities
  (let [next-eid-atom @#'io.pedestal.app.tree/next-eid-atom
        transforms->entities #'io.pedestal.app.tree/transforms->entities]
    (reset! next-eid-atom 10)
    (let [result (transforms->entities {:navigate [{:page :page/configuration}
                                               {msg/topic :y :style :awesome}]
                                    :subscribe [{msg/topic :model/timeline :interval 'interval}]}
                                   1)]
      (is (= (count result) 5))
      (is (= (set (map :t/id result)) #{11 12 13 14 15}))
      (is (= (set (keep :t/transform result)) #{11 12}))
      (is (= (set (map #(dissoc % :t/id :t/transform) result))
             #{{:t/transform-name :subscribe :t/node 1 :t/type :t/transform}
               {:interval 'interval :t/type :t/message msg/topic :model/timeline}
               {:t/transform-name :navigate :t/node 1 :t/type :t/transform}
               {:page :page/configuration :t/type :t/message}
               {:style :awesome :t/type :t/message msg/topic :y}})))))

(deftest test-attrs-entities
  (let [next-eid-atom @#'io.pedestal.app.tree/next-eid-atom
        attrs->entities #'io.pedestal.app.tree/attrs->entities]
    (reset! next-eid-atom 10)
    (is (= (attrs->entities {:color :red :size 10} 1)
           [{:color :red :size 10 :t/id 11 :t/node 1 :t/type :t/attrs}]))))

(deftest test-node->entites
  (let [next-eid-atom @#'io.pedestal.app.tree/next-eid-atom
        node->entities #'io.pedestal.app.tree/node->entities]
    (reset! next-eid-atom 10)
    (testing "empty node with no parent"
      (is (= (node->entities {} [:a :b] nil 5)
             [{:t/id 5 :t/path [:a :b] :t/segment :b :t/type :t/node}])))
    (testing "empty node with parent"
      (is (= (node->entities {} [:a :b] 1 5)
             [{:t/id 5 :t/parent 1 :t/path [:a :b] :t/segment :b :t/type :t/node}])))
    (testing "node with value"
      (is (= (node->entities {:value 42} [:a :b] 1 5)
             [{:t/id 5 :t/parent 1 :t/path [:a :b] :t/segment :b :t/type :t/node :t/value 42}])))
    (testing "node with value and attributes"
      (reset! next-eid-atom 10)
      (is (= (node->entities {:value 42
                              :attrs {:color :green}} [:a :b] 1 5)
             [{:t/id 5 :t/parent 1 :t/path [:a :b] :t/segment :b :t/type :t/node :t/value 42}
              {:color :green :t/id 11 :t/node 5 :t/type :t/attrs}])))
    (testing "node with everything"
      (reset! next-eid-atom 10)
      (is (= (node->entities {:value 42
                              :attrs {:color :green}
                              :transforms {:test [{:x :y}]}} [:a :b] 1 5)
             [{:t/id 5 :t/parent 1 :t/path [:a :b] :t/segment :b :t/type :t/node :t/value 42}
              {:color :green :t/id 11 :t/node 5 :t/type :t/attrs}
              {:t/transform-name :test :t/id 12 :t/node 5 :t/type :t/transform}
              {:x :y :t/transform 12 :t/id 13 :t/type :t/message}])))))

(deftest test-tree->entities
  (let [tree->entities #'io.pedestal.app.tree/tree->entities]
    (let [ui new-app-model
          deltas [{:a {:value 42
                       :attrs {:color :red :size 10}
                       :transforms {:x [{:y :z}]}
                       :children {:b
                                  {:c
                                   [{:value 2
                                     :transforms {:f [{:x :p}]}}
                                    {:value 3
                                     :attrs {:color :blue}}]}}}}]
          tree (test-apply-deltas ui deltas)
          result (tree->entities (:tree tree) [] 1)]
      (is (= (count (filter #(= (:t/type %) :t/node) result))
             6))
      (is (= (count (filter #(= (:t/type %) :t/attrs) result))
             2))
      (is (= (count (filter #(= (:t/type %) :t/transform) result))
             2))
      (is (= (count (filter #(= (:t/type %) :t/message) result))
             2))
      (is (= (count result)
             12)))))

(deftest test-entity->tuples
  (let [entity->tuples #'io.pedestal.app.tree/entity->tuples]
    (is (= (entity->tuples {:t/id 7 :color :red :size 10})
           [[7 :color :red]
            [7 :size 10]]))))

(deftest test-tree->tuples
  (let [tree->tuples #'io.pedestal.app.tree/tree->tuples]
    (let [ui new-app-model
          deltas [{:a {:value 42
                       :attrs {:color :red :size 10}
                       :transforms {:x [{:y :z}]}
                       :children {:b
                                  {:c
                                   [{:value 2
                                     :transforms {:f [{:x :p}]}}
                                    {:value 3
                                     :attrs {:color :blue}}]}}}}]
          tree (test-apply-deltas ui deltas)
          result (tree->tuples tree)]
      (is (= (count (filter #(= (second %) :t/path) result))
             6))
      (is (= (count (filter #(= (last %) :t/node) result))
             6))
      (is (= (count (filter #(= (last %) :t/attrs) result))
             2))
      (is (= (count (filter #(= (last %) :t/transform) result))
             2))
      (is (= (count (filter #(= (last %) :t/message) result))
             2))
      (is (= (count (filter #(= (second %) :t/type) result))
             12)))))

(def deltas
  [{:navigation
    {:title {:value "Datomic"}
     :items [{:value "Configuration"
              :attrs {:active true}
              :transforms {:navigate [{:page :page/configuration}]}}
             {:value "Timeline"
              :transforms {:navigate [{:page :page/timeline}]}}
             {:value "Attributes"
              :transforms {:navigate [{:page :page/attributes}]}}
             {:value "Entities"
              :transforms {:navigate [{:page :page/entity-navigator (msg/param :eid) {}}
                                  {msg/topic :model/entity-navigator msg/type :entity-selected (msg/param :eid) {}}]}}
             {:value "Actions"
              :attrs {:active true}
              :children [{:value "Disconnect"
                          :transforms {:disconnect [{msg/topic :model/configuration}
                                                {msg/type :navigate :page :page/configuration}]}}
                         {:value "Subscribe"
                          :attrs {:color :blue :active true}
                          :transforms {:subscribe [{msg/topic :model/timeline (msg/param :interval) {}}]}}]}]}}])

(def test-tree
  (apply-deltas new-app-model deltas))

(deftest test-tree-queries
  (testing "query for"
    (testing "all path segments"
      (is (= (set (q '[:find ?s
                       :where
                       [?n :t/segment ?s]]
                     test-tree))
             #{[nil] [0] [1] [2] [3] [4] [:navigation] [:title] [:items]})))
    (testing "the path to all active nodes"
      (is (= (set (q '[:find ?p
                       :where
                       [?a :active true]
                       [?a :t/node ?n]
                       [?n :t/path ?p]]
                     test-tree))
             #{[[:navigation :items 4]]
               [[:navigation :items 4 1]]
               [[:navigation :items 0]]})))
    (testing "the value and path of the node which has an transform with a message
              with key :transform and value :entity-selected"
      (is (= (set (q `[:find ?v ?p
                       :where
                       [?m ~msg/type :entity-selected]
                       [?m :t/transform ?e]
                       [?e :t/node ?n]
                       [?n :t/value ?v]
                       [?n :t/path ?p]]
                     test-tree))
             #{["Entities" [:navigation :items 3]]})))
    (testing "the paths to all children of all nodes that have an attribute of :active set to true."
      (is (= (set (q '[:find ?p
                       :where
                       [?a :active true]
                       [?a :t/node ?n]
                       [?child :t/parent ?n]
                       [?child :t/path ?p]]
                     test-tree))
             #{[[:navigation :items 4 0]]
               [[:navigation :items 4 1]]})))
    (testing "the paths to all transforms on this page"
      (is (= (set (q '[:find ?p ?e-name :where
                       [?e :t/transform-name ?e-name]
                       [?e :t/node ?n]
                       [?n :t/path ?p]]
                     test-tree))
             #{[[:navigation :items 0] :navigate]
               [[:navigation :items 1] :navigate]
               [[:navigation :items 2] :navigate]
               [[:navigation :items 3] :navigate]
               [[:navigation :items 4 0] :disconnect]
               [[:navigation :items 4 1] :subscribe]})))
    (testing "the paths to all :navigate transforms on this page"
      (is (= (set (q '[:find ?p :where
                       [?e :t/transform-name :navigate]
                       [?e :t/node ?n]
                       [?n :t/path ?p]]
                     test-tree))
             #{[[:navigation :items 0]]
               [[:navigation :items 1]]
               [[:navigation :items 2]]
               [[:navigation :items 3]]})))
    (testing "the attributes of nodes that send a message to :model/timeline"
      (is (= (set (remove #(= (first %) :t/node)
                          (q `[:find ?attr ?value :where
                               [?m ~msg/topic :model/timeline]
                               [?m :t/transform ?e]
                               [?e :t/node ?n]
                               [?a :t/type :t/attrs]
                               [?a :t/node ?n]
                               [?a ?attr ?value]]
                             test-tree)))
             #{[:active true]
               [:color :blue]
               [:t/type :t/attrs]})))
    (testing "the transform names with a msg/topic key in the message"
      (is (= (set (remove #(= (first %) :t/node)
                          (q `[:find ?n :where
                               [?m ~msg/topic]
                               [?m :t/transform ?e]
                               [?e :t/transform-name ?n]]
                             test-tree)))
             #{[:disconnect] [:navigate] [:subscribe]})))))

(def page-views
  [[:page/configuration 64]
   [:page/timeline 100]
   [:page/attributes 543]
   [:page/entity-navigator 22]])

(deftest test-multiple-data-sources
  (is (= (set (q '[:find ?p ?c
                   :in $ $views
                   :where
                   [?n :t/value "Attributes"]
                   [?e :t/node ?n]
                   [$ ?m :t/transform ?e]
                   [$ ?m :page ?p]
                   [$views ?p ?c]]
                 test-tree
                 page-views))
         #{[:page/attributes 543]})))

(deftest test-query-parameters
  (is (= (set (q '[:find ?p ?c
                   :in $ ?a-name $views
                   :where
                   [$ ?n :t/value ?a-name]
                   [$ ?e :t/node ?n]
                   [$ ?m :t/transform ?e]
                   [$ ?m :page ?p]
                   [$views ?p ?c]]
                 test-tree
                 "Attributes"
                 page-views))
         #{[:page/attributes 543]})))
