; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns test-runner
  (:require [io.pedestal.app.data.test.tracking-map :as test-tracking-map]
            [io.pedestal.app.test.dataflow :as test-dataflow]))

(set-print-fn! js/print)

(test-tracking-map/test-changes)
(test-tracking-map/test-as-map)
(test-dataflow/test-multiple-deep-changes)


(println "Tests completed without exception")
