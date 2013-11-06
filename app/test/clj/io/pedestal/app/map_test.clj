; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.map-test
  (:require [clojure.test :refer :all]
            [io.pedestal.app.helpers :as helpers]
            [io.pedestal.app.match :as match]
            [io.pedestal.app.map :refer :all :as m])
  (:use [clojure.core.async :only [go chan <! >! <!! put! alts! alts!! timeout close!]]))

(defn ttf
  "Return a function which returns transforms for an inform message.
  The produced function will return one tranform message which
  contains one transformation entry for each event entry."
  [k]
  (fn [inform]
    [(mapv (fn [[source event old new]]
             [(conj source k) :swap [event old new]]) inform)]))

(def test-config
  [[(ttf :t1) [:*] :*]
   [(ttf :t2) [:b] :*]
   [(ttf :t3) [:d :*] :*]
   [(ttf :t4) [:d :f] :*]
   [(ttf :t5) [:d :g :*] :*]
   [(ttf :t6) [:d :g :j] :*]
   [(ttf :t7) [:a] :* [:d :f] :*]
   [(ttf :t8) [:b] :* [:d :*] :*]
   [(ttf :t9) [:**] :*]])

(deftest inform-to-transforms-tests
  (let [idx (match/index test-config)]
    (testing "generate correct transforms"
      ;; Instead of passing the actual old and new states, we will
      ;; only pass the keywords :x and :y. These, along with the
      ;; source path should be echoed back in the transforms.
      (is (= (set (inform-to-transforms idx [[[:a] :click :x :y]]))
             #{[[[:a :t1] :swap [:click :x :y]]]
               [[[:a :t7] :swap [:click :x :y]]]
               [[[:a :t9] :swap [:click :x :y]]]}))
      (is (= (set (inform-to-transforms idx [[[:b] :click :x :y]]))
             #{[[[:b :t1] :swap [:click :x :y]]]
               [[[:b :t2] :swap [:click :x :y]]]
               [[[:b :t8] :swap [:click :x :y]]]
               [[[:b :t9] :swap [:click :x :y]]]}))
      (is (= (set (inform-to-transforms idx [[[:d :e] :click :x :y]]))
             #{[[[:d :e :t3] :swap [:click :x :y]]]
               [[[:d :e :t8] :swap [:click :x :y]]]
               [[[:d :e :t9] :swap [:click :x :y]]]}))
      (is (= (set (inform-to-transforms idx [[[:b] :click :z :w] [[:d :e] :click :x :y]]))
             #{[[[:b :t1] :swap [:click :z :w]]]
               [[[:b :t2] :swap [:click :z :w]]]
               [[[:d :e :t3] :swap [:click :x :y]]]
               [[[:b :t8] :swap [:click :z :w]]
                [[:d :e :t8] :swap [:click :x :y]]]
               [[[:b :t9] :swap [:click :z :w]]
                [[:d :e :t9] :swap [:click :x :y]]]})))))

(deftest inform->transforms-tests
  (let [transform-c (chan 10)
        inform-c (inform->transforms test-config transform-c)]
    (put! inform-c [ [[:a] :click :x :y] ])
    (let [v (helpers/take-n 3 transform-c)]
      (is (= (set v)
             #{[[[:a :t1] :swap [:click :x :y]]]
               [[[:a :t7] :swap [:click :x :y]]]
               [[[:a :t9] :swap [:click :x :y]]]})))
    (close! inform-c))

  (testing "widgets use case"
    (let [transform-c (chan 10)
          ;; widget events arrive one at a time
          args-fn (fn [_ inform] [(first inform)])
          ;; when a login inform message is received, produce
          ;; transforms which will update the login widget and ask
          ;; services to authenticate this user
          login-transforms (fn [[_ _ {e :email p :password}]]
                             [[[[:ui :login] :authenticating e]
                               [[:services :auth] :authenticate e p]]])
          ;; create a config to watch for changes to the login form
          config [[login-transforms [:ui :login] :*]]
          ;; create the dispatcher
          inform-c (inform->transforms config transform-c args-fn)]
      (put! inform-c [[[:ui :login] :submit {:email "j@c.com" :password "1234"}]])
      (let [[v] (helpers/take-n 1 transform-c)]
        (is (= v
               [[[:ui :login] :authenticating "j@c.com"]
                [[:services :auth] :authenticate "j@c.com" "1234"]])))
      (close! inform-c)))

  (testing "information model use case"
    (let [transform-c (chan 10)
          ;; for the information model, each i->t function will take
          ;; three arguments: change paths, the old model and the new model
          args-fn (fn [patterns inform]
                    ;; assumes that every inform has the same old and
                    ;; new state
                    (let [change-paths (map first inform)
                          [source event old new] (first inform)]
                      [change-paths old new]))
          sum-of-change (fn [change-paths old new]
                          [[[[:c :a] :swap (reduce (fn [a path] (+ a (get-in new path)))
                                                   0
                                                   change-paths)]]])
          config [[sum-of-change [:a :*] :*]]
          inform-c (inform->transforms config transform-c args-fn)]
      (put! inform-c [[[:a :c] :update {:a {:b 1 :c 2}} {:a {:b 2 :c 3}}]
                      [[:a :b] :update {:a {:b 1 :c 2}} {:a {:b 2 :c 3}}]])
      (let [[v] (helpers/take-n 1 transform-c)]
        (is (= v
               [[[:c :a] :swap 5]])))
      (close! inform-c))))
