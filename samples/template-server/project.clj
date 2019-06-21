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

(defproject template-server "0.5.5"
  :description "a sample that demonstrates using template libraries with pedestal"
  :url "https://pedestal.io/samples/index"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [io.pedestal/pedestal.service "0.5.5"]
                 [io.pedestal/pedestal.jetty "0.5.5"]
                 [ch.qos.logback/logback-classic "1.2.3" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]
                 [hiccup "1.0.5"]
                 [enlive "1.1.6"]
                 [comb "0.1.1"]
                 [org.antlr/stringtemplate "4.0.2"]
                 [de.ubercode.clostache/clostache "1.4.0"]
                 [selmer "1.12.6"]
]



  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "template-server.server/run-dev"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.5.5"]]}}
  :main ^{:skip-aot true} template-server.server)
