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

(defproject fast-pedestal "0.5.1"
  :description "Demonstrate high performance Pedestal with direct Jetty APIs"
  :url "http://pedestal.io/samples/index"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [io.pedestal/pedestal.service "0.5.1"]
                 [io.pedestal/pedestal.jetty "0.5.1"]
                 [ch.qos.logback/logback-classic "1.1.7" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.21"]
                 [org.slf4j/jcl-over-slf4j "1.7.21"]
                 [org.slf4j/log4j-over-slf4j "1.7.21"]]
  :min-lein-version "2.0.0"
  :global-vars {;*warn-on-reflection* true
                ;*unchecked-math* :warn-on-boxed
                ;*compiler-options* {:disable-locals-clearing true}
                *assert* true}
  :pedantic? :abort
  :resource-paths ["config", "resources"]
  :plugins [[info.sunng/lein-bootclasspath-deps "0.1.1"]]
  :boot-dependencies [;; See: https://www.eclipse.org/jetty/documentation/current/alpn-chapter.html#alpn-versions
                      ;[org.mortbay.jetty.alpn/alpn-boot "8.1.4.v20150727"] ;; JDK 1.8.0_51
                      ;[org.mortbay.jetty.alpn/alpn-boot "8.1.3.v20150130"] ;; JDK 1.8.0_31/40/45
                      ;[org.mortbay.jetty.alpn/alpn-boot "8.1.2.v20141202"] ;; JDK 1.8.0_25
                      [org.mortbay.jetty.alpn/alpn-boot "8.1.0.v20141016" :prepend true] ;; JDK 1.8.0_20 (1.8 up to _20)
                      ]
  :profiles {:dev {:aliases {"run-fastjetty" ["trampoline" "run" "-m" "fast-pedestal.fastjetty-service/-main"]
                             "run-fasterjetty" ["trampoline" "run" "-m" "fast-pedestal.fasterjetty-service/-main"]}}
             :uberjar {:aot [fast-pedestal.server]}}
  :main ^{:skip-aot true} fast-pedestal.server
  :jvm-opts ^:replace [;; Turn on Clojure's Direct Linking
                       "-D\"clojure.compiler.direct-linking=true\""
                       ;; Turn off Pedestal's Metrics
                       "-D\"io.pedestal.defaultMetricsRecorder=nil\""
                       "-d64" "-server"
                       "-Xms1g"                             ;"-Xmx1g"
                       "-XX:+UnlockCommercialFeatures"      ;"-XX:+FlightRecorder"
                       ;"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8030"
                       "-XX:+UseG1GC"
                       ;"-XX:+UseConcMarkSweepGC" "-XX:+UseParNewGC" "-XX:+CMSParallelRemarkEnabled"
                       ;"-XX:+ExplicitGCInvokesConcurrent"
                       "-XX:+AggressiveOpts"
                       ;-XX:+UseLargePages
                       "-XX:+UseCompressedOops"]
  )
