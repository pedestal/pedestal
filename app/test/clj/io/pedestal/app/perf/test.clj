; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.perf.test
  (:require [clojure.pprint :as pp]
            [clojure.set :as set]
            [io.pedestal.app.model :as model]
            [io.pedestal.app.perf.model.naive :as naive]
            [io.pedestal.app.perf.model.complex :as complex]
            [io.pedestal.app.perf.model.hybrid :as hybrid]
            [io.pedestal.app.helpers :as helpers]))

(defn- generate-model [[size & sizes]]
  (if size
    (reduce (fn [a b]
              (assoc a b (generate-model sizes)))
            {}
            (range size))
    (rand-int 100)))

(defn- element-count [sizes]
  (apply * sizes))

(defn time*
  "Calculate the time, in milliseconds, to run the passed expression. Returns
  a sequence of maps containing the times and return values of each run."
  ([expr]
     (time* 1 expr identity))
  ([n expr]
     (time* n expr identity))
  ([n expr f]
     (map (fn [_] (let [start (. System (nanoTime))
                        ret (expr)
                        stop (. System (nanoTime))]
                    {:time (/ (double (- stop start)) 1000000.0)
                     :result (f ret)}))
          (range 0 n))))

(defn sqr [x]
  (* x x))

(defn mean [xs]
  (/ (reduce + xs) (count xs)))

(defn sd [xs]
  (let [m (mean xs)]
    (/ (reduce + (map #(sqr (- m %)) xs)) (count xs))))

(def nf (java.text.DecimalFormat. "0.000"))

(defn format [n]
  (.format nf n))

(defn print-results [{:keys [shape desc n]} results]
  (println)
  (println (str (reduce * shape)
                " items "
                (apply str (interpose "-" shape))
                " "
                desc
                " / "
                n " runs"))
  (let [sr (sort-by :mean results)
        quick (:mean (first sr))
        sr (map #(assoc % :scale (/ (:mean %) quick)) sr)]
    (pp/print-table (map #(-> %
                              (update-in [:scale] format)
                              (update-in [:mean] format)
                              (update-in [:sd] format))
                         sr))))

(defn stats [t]
  (let [xs (map :time t)]
    {:mean (mean xs)
     :sd (sd xs)}))

(defn inform= [a e]
  (= a e))

(defn peset [i]
  (set (mapv (fn [[p e]] [p e]) i)))

(defn event= [a b]
  (= (peset a) (peset b)))

(def runners
  [{:name :simple :f model/apply-transform :correct true}
   {:name :naive :f naive/apply-transform}
   {:name :complex :f complex/apply-transform}
   {:name :hybrid :f hybrid/apply-transform}])

(defn run-test [n model transform]
  (let [{f :f} (first (filter :correct runners))
        expected (helpers/ideal-change-report model (:model (f model transform)))]
    (mapv (fn [{:keys [name f]}]
            (let [inform (set (:inform (f model transform)))
                  expr #(count (f model transform))]
              (merge (stats (time* n expr))
                     {:m name
                      :changes (count inform)
                      :inform (inform= inform expected)
                      :event (event= inform expected)})))
          runners)))

(def tests
  [(let [update-f (fn [m] (dissoc m 2))]
     {:desc "dissoc node" :shape [1 3 1] :n 1 :inform [[[0] update-f]]})

   {:desc "inc" :shape [10 10 10] :n 100 :inform [[[7 4 5] inc]]}

   (let [update-f (fn [m] (-> m
                             (update-in [87 5] inc)
                             (update-in [35 9] inc)))]
     {:desc "update-in node" :shape [10 100 10] :n 100 :inform [[[9] update-f]]})

   (let [update-f (fn [m] (-> m
                             (dissoc 20)
                             (update-in [60 9] inc)))]
     {:desc "dissoc node/update-in node" :shape [10 100 10] :n 100 :inform [[[9] update-f]]})

   {:desc "inc" :shape [10 10 10 10 10 10] :n 100 :inform [[[7 4 5 3 8 1] inc]]}

   {:desc "inc" :shape [100 1000 10] :n 100 :inform [[[67 500 8] inc]
                                                     [[42 400 8] inc]
                                                     [[87 300 8] inc]]}

   {:desc "inc" :shape [10 10000 10] :n 100 :inform [[[3 5000 8] inc]
                                                     [[7 400 8] inc]
                                                     [[9 3000 8] inc]]}

   {:desc "inc" :shape [1000 1000] :n 100 :inform [[[500 500] inc]
                                                   [[200 900] inc]
                                                   [[700 100] inc]]}

   (let [update-f (fn [m] (-> m
                             (update-in [2000 5] inc)
                             (update-in [6000 9] inc)))]
     {:desc "update-in node" :shape [10 10000 10] :n 100 :inform [[[9] update-f]]})

   (let [update-f (fn [m] (-> m
                             (dissoc 2000)
                             (update-in [6000 9] inc)))]
     {:desc "dissoc node/update-in node" :shape [10 10000 10] :n 100 :inform [[[9] update-f]]})

   (let [m (generate-model [10])]
     {:desc "merge" :shape [10 10000 10] :n 100 :inform [[[9 5000] merge m]]})

   (let [m (generate-model [10 10])]
     {:desc "merge" :shape [10 10000 10] :n 100 :inform [[[9] merge m]]})

   (let [m (generate-model [10])]
     {:desc "replace" :shape [10 10000 10] :n 100 :inform [[[9 1000] (constantly m)]]})

   (let [m (generate-model [10 100])]
     {:desc "replace" :shape [10 10000 10] :n 10 :inform [[[9] (constantly m)]]})])

(defn -main []
  (doseq [{:keys [desc shape n inform] :as t} tests]
    (print-results t (run-test n (generate-model shape) inform))))
