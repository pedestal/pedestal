; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

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
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as http]
            [template-server.service :as service]))

(def service
  (::http/service-fn (http/create-servlet service/service)))

(deftest test-templates-generate-correct-bodies
  (are [url partial-body-string]
       (.contains (->> url
                       (response-for service :get)
                       :body)
                  partial-body-string)
       "/hiccup"         "Hiccup"
       "/enlive"         "Enlive"
       "/clostache"      "Clostache"
       "/stringtemplate" "String Template"
       "/comb"           "Comb"))
