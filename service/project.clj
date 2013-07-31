; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(defproject io.pedestal/pedestal.service "0.1.11-SNAPSHOT"
  :description "Pedestal Service"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 ;; logging
                 [org.slf4j/slf4j-api "1.7.2"]

                 ;; route
                 [org.clojure/core.incubator "0.1.2"]

                 ;; interceptors
                 [ring/ring-core "1.2.0-beta1"
                  :exclusions [javax.servlet/servlet-api]]
                 [cheshire "5.2.0"]]
  :min-lein-version "2.0.0"
  :java-source-paths ["java"]
  :javac-options ["-target" "1.5" "-source" "1.5"]
  :global-vars {*warn-on-reflection* true}
  :aliases {"bench-log" ["trampoline" "run" "-m" "io.pedestal.service.log-bench"]
            "dumbrepl" ["trampoline" "run" "-m" "clojure.main/main"]}
  :profiles {:default [:dev :provided :user :base]
             :provided
             {:dependencies [[javax.servlet/javax.servlet-api "3.0.1"]]}
             :dev
             {:source-paths ["dev" "src" "bench"]
              :dependencies [[criterium "0.3.1"]
                             [org.clojure/java.classpath "0.2.0"]
                             [org.clojure/tools.namespace "0.2.2"]
                             [clj-http "0.6.4"]
                             [io.pedestal/pedestal.jetty "0.1.11-SNAPSHOT"]
                             [javax.servlet/javax.servlet-api "3.0.1"]
                             ;; Logging:
                             [ch.qos.logback/logback-classic "1.0.7" :exclusions [org.slf4j/slf4j-api]]
                             [org.clojure/tools.logging "0.2.4"]
                             [org.slf4j/jul-to-slf4j "1.7.2"]
                             [org.slf4j/jcl-over-slf4j "1.7.2"]
                             [org.slf4j/log4j-over-slf4j "1.7.2"]]
              :repositories
              [["sonatype-oss"
                "https://oss.sonatype.org/content/groups/public/"]]}})
