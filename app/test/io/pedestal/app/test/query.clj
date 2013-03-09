;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.app.test.query
  (:use io.pedestal.app.query
        clojure.test))

(deftest test-unifier
  (is (= (unifier {} '[?p :name ?parent] [1 :name :john])
         '{?p 1 ?parent :john}))
  (is (= (unifier {} '[?p :age ?parent] [1 :name :john])
         nil)))

(deftest test-combine-unifiers
  (is (= (combine-unifiers '[{?p-name :bob ?p 1}
                             {?p-name :sam ?p 2}
                             {?p-name :dave ?p 3}
                             {?p-name :alice ?p 4}]
                           '[{?c 1}])
         '[{?c 1}]))
  (is (= (combine-unifiers '[{?p-name :bob ?p 1}
                             {?p-name :sam ?p 2}
                             {?p-name :dave ?p 3}
                             {?p-name :alice ?p 4}]
                           '[{?age 4 ?p 1} {?age 42 ?p 2} {?age 33 ?p 3}])
         '[{?age 4 ?p 1 ?p-name :bob}
           {?age 42 ?p 2 ?p-name :sam}
           {?age 33 ?p 3 ?p-name :dave}])))


(deftest test-fold
  (is (= (fold '[[{?p-name :bob ?p 1}
                  {?p-name :sam ?p 2}
                  {?p-name :dave ?p 3}
                  {?p-name :alice ?p 4}]
                 [{?c 1}]
                 [{?age 4 ?p 1} {?age 42 ?p 2} {?age 33 ?p 3}]
                 [{?p 2 ?c 1} {?p 3 ?c 1} {?p 4 ?c 2}]])
         '[[{?age 42 ?c 1 ?p 2 ?p-name :sam}
            {?age 33 ?c 1 ?p 3 ?p-name :dave}]])))


;; Simple Queries
;; ================================================================================

(def facts
  [[1 :name :mary]
   [1 :age 4]
   [2 :name :sue]
   [2 :age 2]
   [3 :name :dan]
   [3 :age 42]
   [4 :name :sally]
   [4 :age 35]
   [5 :name :alice]
   [5 :age 60]
   [6 :name :bob]
   [6 :age 65]
   [1 :parent 3]
   [1 :parent 4]
   [3 :parent 5]
   [3 :parent 6]])

(deftest test-simple-queries
  (is (= (set (q '[:find ?e ?a ?v :where [?e ?a ?v]] facts))
         (set facts)))
  (is (= (set (map first (q '[:find ?n :where [?e :name ?n]] facts)))
         #{:mary :sue :dan :sally :alice :bob}))
  (is (= (set (q '[:find ?parent :where
                   [?p :name ?parent]
                   [?e :parent ?p]
                   [?e :name :mary]]
                 facts))
         #{[:dan] [:sally]}))
  (is (= (set (q '[:find ?parent
                   :where
                   [?e :name :claire]
                   [?e :parent ?p]
                   [?p :name ?parent]]
                 facts))
         #{})))
