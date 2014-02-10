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

(ns template-server.service-test
  (:require [clojure.test :refer :all]
            [io.pedestal.service.test :refer :all]
            [io.pedestal.service.http :as bootstrap]
            [template-server.service :as service]))

(def service
  (::bootstrap/service-fn (bootstrap/create-servlet service/service)))

(deftest test-templates-generate-correct-bodies
  (are [url partial-body-string]
       (.contains (->> url
                       (response-for service :get)
                       :body)
                  partial-body-string)
       "/hiccup" "<p>Hello from Hiccup</p>"
       "/enlive" "<p id=\"the-text\">Hello from the Enlive demo page. Have a nice day!</p>"
       "/mustache" "<p id=\"the-text\">Hello from the Mustache demo page. Have a great day!</p>"
       "/stringtemplate" "<h1>Hello from String Template</h1>"
       "/comb" "<p>This is not erb</p>"))
