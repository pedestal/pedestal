; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app-tools.test.build
  (:require [io.pedestal.app.protocols :as p]
            [io.pedestal.app.messages :as msg]
            [io.pedestal.app.tree :as tree])
  (:use io.pedestal.app-tools.build
        clojure.test))

(deftest test-split-path
  (let [split-path #'io.pedestal.app-tools.build/split-path
        path (str (clojure.java.io/file "some" "path"))] ; Behold, the ugliness that is varargs 
    (is (= (split-path (str path)) ["some" "path"]))))
