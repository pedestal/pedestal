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

(defproject gzip "0.5.9"
  :description "a sample demonstrating container-specific configuration"
  :url "https://pedestal.io/samples/index"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [io.pedestal/pedestal.service "0.5.9"]

                 ;; This samples is specific to jetty, so
                 ;; other options don't appear here.
                 [io.pedestal/pedestal.jetty "0.5.9"]
                 [org.eclipse.jetty/jetty-servlets "9.4.10.v20180503"]
                 [org.eclipse.jetty/jetty-servlet "9.4.53.v20231009"]
                 [ch.qos.logback/logback-classic "1.2.10" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.35"]
                 [org.slf4j/jcl-over-slf4j "1.7.35"]
                 [org.slf4j/log4j-over-slf4j "1.7.35"]
                 [clj-http "3.12.3"]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "gzip.server/run-dev"]}}}
  :main ^{:skip-aot true} gzip.server)
