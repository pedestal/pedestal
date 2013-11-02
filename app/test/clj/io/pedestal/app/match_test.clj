; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.match-test
  (:require [clojure.test :refer :all]
            [io.pedestal.app.match :refer :all :as m]))

(deftest index-test
  (testing "create correct index"
    (is (= (index [[:f1 [:a :*] :*]
                   [:f2 [:a :b] :*]
                   [:f3 [:a :**] :*]
                   [:f4 [:a :b] :* [:a :c] :*]
                   [:f5 [:a :d] :click]])
           {:a {:*  {:* {::m/items [[:f1 #{[[:a :*] :*]}]]}}
                :** {:* {::m/items [[:f3 #{[[:a :**] :*]}]]}}
                :b  {:* {::m/items [[:f2 #{[[:a :b] :*]}] [:f4 #{[[:a :b] :*] [[:a :c] :*]}]]}}
                :c  {:* {::m/items [[:f4 #{[[:a :b] :*] [[:a :c] :*]}]]}}
                :d  {:click {::m/items [[:f5 #{[[:a :d] :click]}]]}}}}))))

(deftest match-items-test
  (let [config [[:f1 [:a] :*]
                [:f2 [:a :b] :*]
                [:f3 [:*] :*]
                [:f4 [:a :*] :*]
                [:f5 [:a :b] :* [:c :*] :*]
                [:f6 [:a :**] :*]
                [:f7 [:* :*] :*]
                [:f8 [:* :*] :deleted]]
        idx (index config)]
    (testing "match functions to event entries"
      (is (= (match-items idx [[:a] :click])
             #{[:f1 #{[[:a] :*]} [[:a] :click]]
               [:f3 #{[[:*] :*]} [[:a] :click]]
               [:f6 #{[[:a :**] :*]} [[:a] :click]]}))
      (is (= (match-items idx [[:a :b] :click])
             #{[:f2 #{[[:a :b] :*]} [[:a :b] :click]]
               [:f4 #{[[:a :*] :*]} [[:a :b] :click]]
               [:f5 #{[[:a :b] :*] [[:c :*] :*]} [[:a :b] :click]]
               [:f6 #{[[:a :**] :*]} [[:a :b] :click]]
               [:f7 #{[[:* :*] :*]} [[:a :b] :click]]}))
      (is (= (match-items idx [[:a :c] :click])
             #{[:f4 #{[[:a :*] :*]} [[:a :c] :click]]
               [:f6 #{[[:a :**] :*]} [[:a :c] :click]]
               [:f7 #{[[:* :*] :*]} [[:a :c] :click]]}))
      (is (= (match-items idx [[:c :d] :click])
             #{[:f7 #{[[:* :*] :*]} [[:c :d] :click]]
               [:f5 #{[[:a :b] :*] [[:c :*] :*]} [[:c :d] :click]]}))
      (is (= (match-items idx [[:c :d] :deleted])
             #{[:f7 #{[[:* :*] :*]} [[:c :d] :deleted]]
               [:f5 #{[[:a :b] :*] [[:c :*] :*]} [[:c :d] :deleted]]
               [:f8 #{[[:* :*] :deleted]} [[:c :d] :deleted]]})))))

(deftest match-test
  (let [config [[:f1 [:a] :*]
                [:f2 [:a :b] :*]
                [:f3 [:*] :*]
                [:f4 [:a :*] :*]
                [:f5 [:a :b] :* [:c :*] :*]
                [:f6 [:**] :*]]
        idx (index config)]
    (testing "join inform messages and functions which process them"
      (is (= (match idx [ [[:a] :click] ])
             #{[:f1 #{[[:a] :*]}                 [ [[:a] :click] ]]
               [:f3 #{[[:*] :*]}                 [ [[:a] :click] ]]
               [:f6 #{[[:**] :*]}                [ [[:a] :click] ]]}))
      (is (= (match idx [ [[:a :b] :click] [[:c :d] :click] ])
             #{[:f6 #{[[:**] :*]}                [ [[:a :b] :click] [[:c :d] :click]  ]]
               [:f2 #{[[:a :b] :*]}              [ [[:a :b] :click] ]]
               [:f4 #{[[:a :*] :*]}              [ [[:a :b] :click] ]]
               [:f5 #{[[:a :b] :*] [[:c :*] :*]} [ [[:a :b] :click] [[:c :d] :click] ]]}))
      (is (= (match idx [ [[:f :g] :click] [[:c :d] :click] ])
             #{[:f6 #{[[:**] :*]}                [ [[:f :g] :click] [[:c :d] :click] ]]
               [:f5 #{[[:a :b] :*] [[:c :*] :*]} [ [[:c :d] :click] ]]})))))
