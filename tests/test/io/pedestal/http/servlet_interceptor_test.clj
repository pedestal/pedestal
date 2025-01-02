; Copyright 2024-2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;u
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.servlet-interceptor-test
  (:require [clj-commons.format.exceptions :as exceptions]
            [io.pedestal.http.impl.servlet-interceptor :as si]
            [clojure.test :refer [deftest is]]
            [clojure.string :as string]
            [matcher-combinators.matchers :as m])
  (:import (jakarta.servlet.http HttpServletRequest)
           (java.io IOException)))

(def create-stylobate @#'si/create-stylobate)

(deftest default-analyzer-returns-exception
  (let [e (RuntimeException.)]
    (is (identical? e
                    (si/default-exception-analyzer nil e)))))

(deftest default-analyzer-returns-nil-for-broken-pipe
  (is (nil?
        (si/default-exception-analyzer nil (IOException. "Broken pipe"))))

  (is (nil?
        (si/default-exception-analyzer nil (RuntimeException.
                                             ^Throwable (ex-info "Wrapped" {}
                                                                 (IOException. "Broken Pipe")))))))

(deftest stylobate-calls-provided-analyzer
  (let [*exception (atom nil)
        f          (fn [_ exception]
                     (reset! *exception exception)
                     nil)
        e          (RuntimeException.)
        context    {:this            :that
                    :servlet-request (reify HttpServletRequest
                                       (isAsyncStarted [_] false))}
        stylobate  (create-stylobate {:exception-analyzer f})]
    (is (identical? context
                    ((:error stylobate) context e)))
    (is (identical? e @*exception))))

(deftest dev-exception-debug-interceptor-invokes-formatter
  (let [e        (RuntimeException. "For testing of formatting")
        expected (binding [exceptions/*fonts* nil]
                   (exceptions/format-exception e))
        f        (:error si/exception-debug)
        context  (f {} e)]
    (is (match?
          {:response {:status 500
                      :body   (m/pred #(string/includes? % expected))}}
          context))))

