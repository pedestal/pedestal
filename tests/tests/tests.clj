; Copyright 2024 Nubank NA
; Copyright 2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns tests
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.build.api :as b]))


(defn run
  [_]
  (doseq [jetty ["jetty-11"]]
    (println "Running tests with" jetty)
    (b/process {:command-args ["clojure" (format "-X:%s:test" jetty)
                               ;; test-runner needs extra dirs
                               ":dirs" (pr-str (into ["test"] [(str "test-" jetty)]))]})))
