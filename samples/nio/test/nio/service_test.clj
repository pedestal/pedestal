; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns nio.service-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as http]
            [nio.service :as service]))

(def service
  (::http/service-fn (http/create-servlet service/service)))

(deftest home-page-test
  (is (=
       (:body (response-for service :get "/"))
       "Hello World!"))
  (is (=
       (:headers (response-for service :get "/"))
       {"Content-Type" "text/plain;charset=UTF-8"
        "Strict-Transport-Security" "max-age=31536000; includeSubdomains"
        "X-Frame-Options" "DENY"
        "X-Content-Type-Options" "nosniff"
        "X-XSS-Protection" "1; mode=block"})))


(deftest about-page-test
  (is (.contains
       (:body (response-for service :get "/about"))
       "Clojure 1.8"))
  (is (=
       (:headers (response-for service :get "/about"))
       {"Content-Type" "text/html;charset=UTF-8"
        "Strict-Transport-Security" "max-age=31536000; includeSubdomains"
        "X-Frame-Options" "DENY"
        "X-Content-Type-Options" "nosniff"
        "X-XSS-Protection" "1; mode=block"})))
