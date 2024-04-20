(ns io.pedestal.chain1v2
  "Tests Pedestal 0.7's interceptor.chain (chain1) vs. 0.8's."
  (:require [io.pedestal.interceptor :refer [interceptor]]
            [clojure.core.async :refer [go chan put!]]
            [io.pedestal.test-common :refer [<!!?]]
            [io.pedestal.interceptor.chain1 :as chain1]
            [io.pedestal.interceptor.chain :as chain2]
            [net.lewisship.bench :refer [bench-for]]))

(defn make-interceptor
  [name stage]
  (interceptor
    {:name name
     stage (fn [context]
             (update context :events conj [stage name]))}))

(defn make-interceptor-async
  [name stage]
  (interceptor
    {:name name
     stage (fn [context]
             (go
               (update context :events conj [stage name])))}))

(defn capture
  [ch]
  (interceptor
    {:enter #(assoc % :events [])
     :leave (fn [context]
              (put! ch (:events context))
              context)}))

(def names [:a :b :c :d :e :f :g :h :i :j :k :l :m :n :o :q :r :s :t])

(def ^:dynamic *bound-value* nil)

(defn binder
  [name]
  (interceptor
    {:name  name
     :enter (fn [context]
              (chain2/bind context *bound-value* name))
     :leave (fn [context]
              (chain2/unbind context *bound-value*))}))

(def bound-reader
  (interceptor
    {:name  :bound-reader
     :enter (fn [context]
              (update context :events conj [:bound-reader *bound-value*]))}))

(def queue-bound-reader-v1
  (interceptor
    {:name  :queue-bound-reader
     :enter #(chain1/enqueue* % bound-reader)}))

(def queue-bound-reader-v2
  (interceptor
    {:name  :queue-bound-reader
     :enter #(chain2/enqueue* % bound-reader)}))

(def exception-catcher
  (interceptor {:name  :catcher
                :error (fn [context error]
                         (update context :events conj [:catcher]))}))

(def exception-thrower
  (interceptor {:name  :thrower
                :enter (fn [context]
                         (throw (IllegalStateException.)))}))

(def interceptors
  (map make-interceptor
       names
       (cycle [:enter :leave])))

(def async-interceptors
  (map make-interceptor-async
       names
       (cycle [:enter :leave])))

(defn execute [execute-fn interceptors]
  (let [chan          (chan)
        interceptors' (into [(capture chan)] interceptors)]
    (execute-fn {} interceptors')
    (<!!? chan)))


(defn example [execute-fn async? count]
  (let [names         (->> (range count)
                           (map #(str "int-" %))
                           (map keyword))
        half-count    (-> (/ count 2) long)
        f             (if async? make-interceptor-async make-interceptor)
        interceptors  (map f names (cycle [:enter :leave]))
        interceptors' (concat
                        (take half-count interceptors)
                        [(binder :binder)
                         (if (= execute-fn chain1/execute)
                           queue-bound-reader-v1
                           queue-bound-reader-v2)]
                        (drop half-count interceptors)
                        [exception-catcher
                         exception-thrower])]
    []
    (execute execute-fn interceptors')))


(comment
  (assert (= (example chain1/execute true 10)
             (example chain2/execute true 10)))


  (assert (=
            (execute chain1/execute interceptors)
            (execute chain2/execute interceptors)))


  (bench-for
    {:quick? false
     :progress? true}
    [async? [false true]
              count [5 10 50 100]]
             (example chain1/execute async? count)
             (example chain2/execute async? count))

  )

