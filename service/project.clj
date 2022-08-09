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

(defproject io.pedestal/pedestal.service "0.5.11-SNAPSHOT"
  :description "Pedestal Service"
  :url "https://github.com/pedestal/pedestal"
  :scm "https://github.com/pedestal/pedestal"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.11.1"]

                 [io.pedestal/pedestal.log "0.5.11-SNAPSHOT"]
                 [io.pedestal/pedestal.interceptor "0.5.11-SNAPSHOT"]
                 [io.pedestal/pedestal.route "0.5.11-SNAPSHOT"]

                 [org.ow2.asm/asm "9.3"]

                 ;; channels
                 [org.clojure/core.async "1.5.648" :exclusions [org.clojure/tools.analyzer.jvm]]

                 ;; interceptors
                 [ring/ring-core "1.9.5" :exclusions [[org.clojure/clojure]
                                                      [org.clojure/tools.reader]
                                                      [crypto-random]
                                                      [crypto-equality]]]

                 [cheshire "5.11.0"]
                 [org.clojure/tools.reader "1.3.6"]
                 [org.clojure/tools.analyzer.jvm "1.2.2"]
                 [com.cognitect/transit-clj "1.0.329"]
                 [commons-codec "1.15"]
                 [commons-io "2.11.0"]
                 [crypto-random "1.2.1" :exclusions [[commons-codec]]]
                 [crypto-equality "1.0.1"]]
  :min-lein-version "2.0.0"
  :java-source-paths ["java"]
  :javac-options ["-target" "11" "-source" "11"]
  :jvm-opts ["-D\"clojure.compiler.direct-linking=true\""]
  :global-vars {*warn-on-reflection* true}
  :pedantic? :abort
  :aliases {"bench-log" ["trampoline" "run" "-m" "io.pedestal.log-bench"]
            "bench-service" ["trampoline" "run" "-m" "io.pedestal.niotooling.server"]
            "bench-route" ["trampoline" "run" "-m" "io.pedestal.route.route-bench"]
            "dumbrepl" ["trampoline" "run" "-m" "clojure.main/main"]
            "docs" ["with-profile" "docs" "codox"]}
  :profiles {:default [:dev :provided :user :base]
             :provided {:dependencies [[javax.servlet/javax.servlet-api "4.0.1"]]}
             :dev {:source-paths ["dev" "src" "bench"]
                   :dependencies [[criterium "0.4.6"]
                                  [org.clojure/java.classpath "1.0.0"]
                                  [org.clojure/tools.namespace "1.3.0"]
                                  [clj-http "3.12.3"]
                                  ;; TODO: While com.ning/async-http-client 1.9.40 is available,
                                  ;; an arity error is encountered when running `lein bench-service`.
                                  ;; Furthermore, the project has been moved to
                                  ;; https://github.com/AsyncHttpClient/async-http-client
                                  ;; So benchmarking should be updated to use that.
                                  [com.ning/async-http-client "1.9.40"]
                                  [org.eclipse.jetty/jetty-servlets "10.0.11" :exclusions [org.slf4j/slf4j-api]]
                                  [io.pedestal/pedestal.jetty "0.5.11-SNAPSHOT"]
                                  [io.pedestal/pedestal.immutant "0.5.11-SNAPSHOT"]
                                  [io.pedestal/pedestal.tomcat "0.5.11-SNAPSHOT"]
                                  [javax.servlet/javax.servlet-api "4.0.1"]
                                  ;; Logging:
                                  [ch.qos.logback/logback-classic "1.2.11" :exclusions [org.slf4j/slf4j-api]]
                                  [org.clojure/tools.logging "1.2.4"]
                                  [org.slf4j/jul-to-slf4j "1.7.36"]
                                  [org.slf4j/jcl-over-slf4j "1.7.36"]
                                  [org.slf4j/log4j-over-slf4j "1.7.36"]

                                  ;; only used for route-bench - remove when no longer needed
                                  [incanter/incanter-core "1.9.3"]
                                  [incanter/incanter-charts "1.9.3"]

                                  ;; only used for tracing test
                                  [io.jaegertracing/jaeger-client "1.8.1" :exclusions [org.jetbrains.kotlin/kotlin-stdlib-common]]]
                   :repositories [["sonatype-oss"
                                   "https://oss.sonatype.org/content/groups/public/"]]}
             :docs {:pedantic? :ranges
                    :plugins [[lein-codox "0.9.5"]]
                    :dependencies [[javax.servlet/javax.servlet-api "4.0.1"]]}}
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
