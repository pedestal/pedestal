; Copyright 2024 Nubank NA
;
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.test-common
  (:require [clj-commons.ansi :as ansi]
            [clojure.core.async :as async]
            [clojure.spec.test.alpha :as stest])
  (:import (java.io StringWriter)))

(defn no-ansi-fixture
  [f]
  (binding [ansi/*color-enabled* false]
    (f)))

(defn <!!?
  "<!! with a timeout to keep tests from hanging."
  ([ch]
   (<!!? ch 1000))
  ([ch timeout]
   (async/alt!!
     ch ([val _] val)
     (async/timeout timeout) ::timeout)))

(defn instrument-specs-fixture
  [f]
  (require 'io.pedestal.http.specs)
  (stest/instrument)
  (try
    (f)
    (finally
      (stest/unstrument))))

(defmacro with-err-str
  "Evaluates exprs in a context in which *err* is bound to a fresh
  StringWriter.  Returns the string created by any nested printing
  calls."
  [& body]
  `(let [s# (new StringWriter)]
     (binding [*err* s#]
       ~@body
       (str s#))))
