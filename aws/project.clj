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

(defproject io.pedestal/pedestal.aws "0.5.11-SNAPSHOT"
  :description "AWS utilities for running Pedestal services on AWS"
  :url "https://github.com/pedestal/pedestal"
  :scm "https://github.com/pedestal/pedestal"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [io.pedestal/pedestal.interceptor "0.5.11-SNAPSHOT"]
                 [io.pedestal/pedestal.log "0.5.11-SNAPSHOT"]
                 ;[com.amazonaws.serverless/aws-serverless-java-container-core "0.5.1" :exclusions [[com.fasterxml.jackson.core/jackson-databind]]]
                 [javax.servlet/javax.servlet-api "4.0.1"]
                 [com.amazonaws/aws-java-sdk-core "1.12.276" :exclusions [commons-logging]] ;; Needed for x-ray
                 [com.amazonaws/aws-lambda-java-core "1.2.1"]
                 ;[com.amazonaws/aws-lambda-java-events "1.3.0"]
                 [com.amazonaws/aws-xray-recorder-sdk-core "2.11.2" :exclusions [com.amazonaws/aws-java-sdk-core
                                                                                commons-logging
                                                                                joda-time]]
                 ;; Deps cleanup
                 [commons-logging "1.2"] ;; A clash between AWS and HTTP Libs
                 [com.fasterxml.jackson.core/jackson-core "2.13.3"] ;; Bring AWS libs inline with Pedestal Service
                 [com.fasterxml.jackson.dataformat/jackson-dataformat-cbor "2.13.3" ;; Bring AWS libs inline with Pedestal Service
                  :exclusions [[com.fasterxml.jackson.core/jackson-annotations]
                               [com.fasterxml.jackson.core/jackson-databind]]] 
                 [commons-codec "1.15"] ;; Bring AWS libs inline with Pedestal Service
                 [joda-time "2.10.14"] ;; Bring AWS libs inline with Pedestal Service
                 ]
  :min-lein-version "2.0.0"
  :global-vars {*warn-on-reflection* true}
  :pedantic? :abort

  :aliases {"docs" ["with-profile" "docs" "codox"]}

  :profiles {:docs {:pedantic? :ranges
                    :plugins [[lein-codox "0.9.5"]]}})
