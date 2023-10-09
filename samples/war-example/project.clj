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

(defproject war-example "0.5.9"
  :description "Demonstrate packaging a Pedestal app in a war file"
  :url "https://pedestal.io/samples/index"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [io.pedestal/pedestal.service "0.5.9"]

                 [ch.qos.logback/logback-classic "1.1.7" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.35"]
                 [org.slf4j/jcl-over-slf4j "1.7.35"]
                 [org.slf4j/log4j-over-slf4j "1.7.35"]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :java-agents [[org.mortbay.jetty.alpn/jetty-alpn-agent "2.0.10"]]
  :profiles {:dev {:aliases {"run-dev" ["with-profiles" "dev,jetty" "trampoline" "run" "-m" "war-example.server/run-dev"]}
                   :dependencies [ [io.pedestal/pedestal.service-tools "0.5.9"]]
                   :plugins [[ohpauleez/lein-pedestal "0.1.0-beta10"]]
                   :pedestal {;:web-xml "war-resources/WEB-INF/web.xml" ;; use this instead of generating
                              :servlet-name "PedestalWarExample"
                              :servlet-display-name "Pedestal WAR Example"
                              :servlet-description "An example of how to build WARs of Pedestal services"
                              :server-ns "war-example.server"}}
             :test {:dependencies [[jakarta.servlet/jakarta.servlet-api "3.1.0"]]}
             :jetty {:dependencies [[io.pedestal/pedestal.jetty "0.5.9"]]}
             :uberjar {:aot [war-example.server]}}
  :main ^{:skip-aot true} war-example.server)
