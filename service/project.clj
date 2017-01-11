; Copyright 2013 Relevance, Inc.
; Copyright 2014-2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(defproject io.pedestal/pedestal.service "0.5.2-SNAPSHOT"
  :description "Pedestal Service"
  :url "https://github.com/pedestal/pedestal"
  :scm "https://github.com/pedestal/pedestal"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]

                 [io.pedestal/pedestal.log "0.5.2-SNAPSHOT"]
                 [io.pedestal/pedestal.interceptor "0.5.2-SNAPSHOT"]
                 [io.pedestal/pedestal.route "0.5.2-SNAPSHOT"]

                 ;; channels
                 [org.clojure/core.async "0.2.391"]

                 ;; interceptors
                 [ring/ring-core "1.5.1" :exclusions [[org.clojure/clojure]
                                                      [org.clojure/tools.reader]
                                                      [crypto-random]
                                                      [crypto-equality]]]

                 ;[com.fasterxml.jackson.core/jackson-core "2.3.2"]
                 [cheshire "5.5.0" :exclusions [[com.fasterxml.jackson.core/jackson-core]]]
                 [com.cognitect/transit-clj "0.8.285"]
                 [commons-codec "1.10"]
                 [crypto-random "1.2.0" :exclusions [[commons-codec]]]
                 [crypto-equality "1.0.0"]]
  :min-lein-version "2.0.0"
  :java-source-paths ["java"]
  :javac-options ["-target" "1.7" "-source" "1.7"]
  :jvm-opts ["-D\"clojure.compiler.direct-linking=true\""]
  :global-vars {*warn-on-reflection* true}
  :pedantic? :abort
  :aliases {"bench-log" ["trampoline" "run" "-m" "io.pedestal.log-bench"]
            "bench-service" ["trampoline" "run" "-m" "io.pedestal.niotooling.server"]
            "bench-route" ["trampoline" "run" "-m" "io.pedestal.route.route-bench"]
            "dumbrepl" ["trampoline" "run" "-m" "clojure.main/main"]
            "docs" ["with-profile" "docs" "codox"]}
  :profiles {:default [:dev :provided :user :base]
             :provided {:dependencies [[javax.servlet/javax.servlet-api "3.1.0"]]}
             :dev {:source-paths ["dev" "src" "bench"]
                   :dependencies [[codox "0.9.5" :exclusions [[org.clojure/tools.reader]]]
                                  [criterium "0.4.4"]
                                  [org.clojure/java.classpath "0.2.3"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  ;[clj-http "0.9.1"]
                                  [clj-http "2.0.0" :exclusions [[potemkin]
                                                                 [clj-tuple]]]
                                  [com.ning/async-http-client "1.8.13"]
                                  [org.eclipse.jetty/jetty-servlets "9.4.0.v20161208"]
                                  [io.pedestal/pedestal.jetty "0.5.2-SNAPSHOT"]
                                  [io.pedestal/pedestal.immutant "0.5.2-SNAPSHOT"]
                                  [io.pedestal/pedestal.tomcat "0.5.2-SNAPSHOT"]
                                  [javax.servlet/javax.servlet-api "3.1.0"]
                                  ;; Logging:
                                  [ch.qos.logback/logback-classic "1.1.8" :exclusions [org.slf4j/slf4j-api]]
                                  [org.clojure/tools.logging "0.3.1"]
                                  [org.slf4j/jul-to-slf4j "1.7.22"]
                                  [org.slf4j/jcl-over-slf4j "1.7.22"]
                                  [org.slf4j/log4j-over-slf4j "1.7.22"]

                                  ;; only used for route-bench - remove when no longer needed
                                  [incanter/incanter-core "1.5.6"]
                                  [incanter/incanter-charts "1.5.6"]]
                   :repositories [["sonatype-oss"
                                   "https://oss.sonatype.org/content/groups/public/"]]}
             :docs {:pedantic? :ranges
                    :plugins [[lein-codox "0.9.5"]]
                    :dependencies [[javax.servlet/javax.servlet-api "3.1.0"]]}}
  ;:jvm-opts ^:replace ["-D\"clojure.compiler.direct-linking=true\""
  ;                     "-d64" "-server"
  ;                     "-Xms1g"                             ;"-Xmx1g"
  ;                     "-XX:+UnlockCommercialFeatures"      ;"-XX:+FlightRecorder"
  ;                     ;"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8030"
  ;                     "-XX:+UseG1GC"
  ;                     ;"-XX:+UseConcMarkSweepGC" "-XX:+UseParNewGC" "-XX:+CMSParallelRemarkEnabled"
  ;                     ;"-XX:+ExplicitGCInvokesConcurrent"
  ;                     "-XX:+AggressiveOpts"
  ;                     ;-XX:+UseLargePages
  ;                     "-XX:+UseCompressedOops"]
  )
