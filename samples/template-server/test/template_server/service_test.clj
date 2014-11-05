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
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as bootstrap]
            [template-server.service :as service]))

(def service
  (::bootstrap/service-fn (bootstrap/create-servlet service/service)))

(defn- text-paragraph [t] (str "<p id=\"the-text\">Hello from the " t " demo page. Have a nice day!</p>"))

(deftest test-templates-generate-correct-bodies
  (are [url partial-body-string]
       (.contains (->> url
                       (response-for service :get)
                       :body)
                  partial-body-string)
       "/hiccup"         (text-paragraph "Hiccup")
       "/enlive"         (text-paragraph "Enlive")
       "/clostache"      (text-paragraph "Clostache")
       "/stringtemplate" (text-paragraph "String Template")
       "/comb"           (text-paragraph "Comb")))
