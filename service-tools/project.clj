; Copyright 2013 Relevance, Inc.
; Copyright 2014-2019 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(defproject io.pedestal/pedestal.service-tools "0.5.11-SNAPSHOT"
  :description "Pedestal tools for service development"
  :url "https://github.com/pedestal/pedestal"
  :scm "https://github.com/pedestal/pedestal"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :dependencies [[io.pedestal/pedestal.service "0.5.11-SNAPSHOT"]
                 [org.clojure/data.xml "0.2.0-alpha5"]

                 ;; Auto-reload changes
                 [ns-tracker "0.4.0"]

                 ;; Logging
                 [ch.qos.logback/logback-classic "1.2.10" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.26"]
                 [org.slf4j/jcl-over-slf4j "1.7.26"]
                 [org.slf4j/log4j-over-slf4j "1.7.26"]

                 [javax.servlet/javax.servlet-api "3.1.0" :scope "test"]]

  :aliases {"docs" ["with-profile" "docs" "codox"]}
  :pedantic? :abort

  :profiles {:docs {:pedantic? :ranges
                    :plugins [[lein-codox "0.9.5"]]}})
