; Copyright 2013 Relevance, Inc.
; Copyright 2014-2018 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(defproject io.pedestal/pedestal.aws "0.5.4-SNAPSHOT"
  :description "AWS utilities for running Pedestal services on AWS"
  :url "https://github.com/pedestal/pedestal"
  :scm "https://github.com/pedestal/pedestal"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [io.pedestal/pedestal.interceptor "0.5.4-SNAPSHOT"]
                 ;[com.amazonaws.serverless/aws-serverless-java-container-core "0.5.1" :exclusions [[com.fasterxml.jackson.core/jackson-databind]]]
                 [javax.servlet/javax.servlet-api "3.1.0"]
                 ;[com.fasterxml.jackson.core/jackson-databind "2.8.9"] ;; matches io.pedestal/pedestal.service
                 [com.amazonaws/aws-lambda-java-core "1.2.0"]
                 ;[com.amazonaws/aws-lambda-java-events "1.3.0"]
                 ]
  :min-lein-version "2.0.0"
  :global-vars {*warn-on-reflection* true}
  :pedantic? :abort

  :aliases {"docs" ["with-profile" "docs" "codox"]}

  :profiles {:docs {:pedantic? :ranges
                    :plugins [[lein-codox "0.9.5"]]}})

