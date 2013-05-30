; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.test.dataflow
  (:require [io.pedestal.app.dataflow :as df]))

(defn test-multiple-deep-changes []
  
  (let [results (atom nil)
        t (fn [state message]
            (-> state
                (update-in [:c] (fnil inc 0))
                (update-in [:d] (fnil inc 0))))
        e (fn [inputs]
            (reset! results inputs)
            [])
        dataflow (df/build {:transform [[:a [:b] t]]
                            :emit [[#{[:* :*]} e]]})]
    (assert (= (df/run {:data-model {:b {}}} dataflow {:key :a :out [:b]})
               {:data-model {:b {:c 1 :d 1}}
                :emit []}))
    (assert (= @results
               {:added #{[:b :c] [:b :d]}
                :input-paths #{[:* :*]}
                :message {:key :a, :out [:b]}
                :new-model {:b {:c 1, :d 1}}
                :old-model {:b {}}
                :removed #{}
                :updated #{}
                :mode nil
                :processed-inputs nil}))))
