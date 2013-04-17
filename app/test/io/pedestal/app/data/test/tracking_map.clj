; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.data.test.tracking-map
  (:use io.pedestal.app.data.tracking-map
        clojure.set
        clojure.test))

(defmacro is-changed [updates expected expected-change]
  `(let [m# ~updates]
     (is (= m# ~expected))
     (is (= (changes m#) ~expected-change))))

(deftest test-changes

  (is-changed (tracking-map {}) {} {})

  (is-changed (assoc (tracking-map {}) :a 1) {:a 1} {:added #{[:a]}})

  (is-changed (assoc (tracking-map {:a 1}) :a 2) {:a 2} {:updated #{[:a]}})

  (is-changed (dissoc (tracking-map {:a 1}) :a) {} {:removed #{[:a]}})

  (is-changed (assoc-in (tracking-map {}) [:a :b] 1)

              {:a {:b 1}}

              {:added #{[:a]}})

  (is-changed (assoc-in (tracking-map {}) [:a :b :c] 1)

              {:a {:b {:c 1}}}

              {:added #{[:a]}})

  (is-changed (assoc-in (tracking-map {:a {:b 2}}) [:a :b] 1)

              {:a {:b 1}}

              {:updated #{[:a :b]}})


  (is-changed (-> (tracking-map {})
                  (assoc :d {:b {}})
                  (assoc-in [:d :b :c] 10)
                  (update-in [:d :b :c] inc))
              
              {:d {:b {:c 11}}}
              
              {:added #{[:d]}})
  
  (is-changed (-> (tracking-map {:a {:b {:c 0}}})
                  (assoc-in [:a :b :e] 10))
              
              {:a {:b {:c 0 :e 10}}}
              
              {:added #{[:a :b :e]}})
  
  (is-changed (-> (tracking-map {:a {:b {:c 0}}})
                  (assoc-in [:a :b :e] 10)
                  (update-in [:a :b :c] inc))
              
              {:a {:b {:c 1 :e 10}}}
              
              {:added #{[:a :b :e]}
               :updated #{[:a :b :c]}})
  
  (is-changed (-> (tracking-map {:a {:b {:c 0 :g 42}}})
                  (assoc-in [:a :b :e] 10)
                  (update-in [:a :b :c] inc)
                  (update-in [:a :b] dissoc :g))
              
              {:a {:b {:c 1 :e 10}}}
              
              {:added #{[:a :b :e]}
               :updated #{[:a :b :c]}
               :removed #{[:a :b :g]}})
  
  (is-changed (assoc (tracking-map {:a {1 :x 2 :y 3 :z}})
                :a
                {1 :m 2 :y 4 42})
              
              {:a {1 :m 2 :y 4 42}}
              
              {:added #{[:a 4]}
               :updated #{[:a 1]}
               :removed #{[:a 3]}})
  
  (is-changed (assoc-in (tracking-map {:a {:b {1 :x 2 :y 3 :z}}})
                        [:a :b]
                        {1 :m 2 :y 4 42})
              
              {:a {:b {1 :m 2 :y 4 42}}}
              
              {:added #{[:a :b 4]}
               :updated #{[:a :b 1]}
               :removed #{[:a :b 3]}})
  
  (is-changed (merge (tracking-map {:a 1 :b 2 :c 3})
                     {:a 2})
              
              {:a 2 :b 2 :c 3}
              
              {:updated #{[:a]}})
  
  (is-changed (update-in (tracking-map {:a 1 :b 2 :c {:a 1 :b 2}})
                         [:c]
                         merge
                         {:a 2})
              
              {:a 1 :b 2 :c {:a 2 :b 2}}
              
              {:updated #{[:c :a]}})
  
  (is-changed (update-in (tracking-map {:a 1 :b 2 :c {:a 1 :b 2}})
                         [:c]
                         #(merge {:a 2} %))
              
              {:a 1 :b 2 :c {:a 1 :b 2}}
              
              {})
  
  (is-changed (update-in (tracking-map {:a {:b {:c 1 :d 2}}})
                         [:a :b]
                         #(merge {:c 2} %))
              
              {:a {:b {:c 1 :d 2}}}
              
              {})

  (is-changed (update-in (tracking-map {:a {:b {:c 1 :d 2}}})
                         [:a :b]
                         #(merge {:z 2} %))
              
              {:a {:b {:c 1 :d 2 :z 2}}}
              
              {:added #{[:a :b :z]}}))

(deftest test-as-map

  (is (= (count (tracking-map {:a 1 :b 2}))
         2))

  (let [m (tracking-map {:a 1 :b 2})]
    (is (= (:a m) 1))
    (is (= (m :a) 1)))

  (is (= (meta (with-meta (tracking-map {:a 5 :b 6}) {:z 42}))
         {:z 42}))

  (is (= (get-in (tracking-map {:a {:b 2 :c 4}}) [:a :c])
         4)))

(deftest same-ancestors
  (is (= (difference (ancestors (class {}))
                     (ancestors (class (tracking-map {}))))
         #{clojure.lang.AFn clojure.lang.APersistentMap})))
