; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app-tools.test.service
  (:use io.pedestal.app-tools.service
        clojure.test))

(deftest test-default-cache-control-to-no-cache-interceptor
  (let [leave (:leave default-cache-control-to-no-cache)
        empty-response {}
        response-with-cache-control {:response {:headers {"Cache-Control" "max-age=42"}}}]
    (is (= (leave {})
           {:response {:headers {"Cache-Control" "no-cache"}}})
        "Response Cache-Control defaults to no-cache")
    (is (= (leave response-with-cache-control)
           response-with-cache-control)
        "Response Cache-Control isn't modified if already specified")))
