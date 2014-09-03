; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(defproject io.pedestal/pedestal.service "0.3.1-SNAPSHOT"
  :description "Pedestal Service"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 ;; logging
                 [org.slf4j/slf4j-api "1.7.7"]

                 ;; route
                 [org.clojure/core.incubator "0.1.3"]

                 ;; channels
                 [org.clojure/core.async "0.1.303.0-886421-alpha"]

                 ;; interceptors
                 [ring/ring-core "1.3.0"
                  :exclusions [[org.clojure/clojure]
                               [org.clojure/tools.reader]
                               [srypto-random]
                               [crypto-equality]]]
                 [org.clojure/tools.reader "0.8.5"]
                 [cheshire "5.3.1"]
                 [commons-codec "1.9"]
                 [crypto-random "1.2.0" :exclusions [[commons-codec]]]
                 [crypto-equality "1.0.0"]]
  :min-lein-version "2.0.0"
  :java-source-paths ["java"]
  :javac-options ["-target" "1.7" "-source" "1.7"]
  :global-vars {*warn-on-reflection* true}
  :aliases {"bench-log" ["trampoline" "run" "-m" "io.pedestal.log-bench"]
            "dumbrepl" ["trampoline" "run" "-m" "clojure.main/main"]}
  :profiles {:default [:dev :provided :user :base]
             :provided
             {:dependencies [[javax.servlet/javax.servlet-api "3.1.0"]]}
             :dev
             {:source-paths ["dev" "src" "bench"]
              :dependencies [[criterium "0.3.1"]
                             [org.clojure/java.classpath "0.2.2"]
                             [org.clojure/tools.namespace "0.2.4"]
                             [clj-http "0.9.1"]
                             [io.pedestal/pedestal.jetty "0.3.1-SNAPSHOT"]
                             [org.eclipse.jetty/jetty-servlets "9.2.0.v20140526"]
                             [io.pedestal/pedestal.tomcat "0.3.1-SNAPSHOT"]
                             [javax.servlet/javax.servlet-api "3.1.0"]
                             ;; Logging:
                             [ch.qos.logback/logback-classic "1.1.2" :exclusions [org.slf4j/slf4j-api]]
                             [org.clojure/tools.logging "0.2.6"]
                             [org.slf4j/jul-to-slf4j "1.7.7"]
                             [org.slf4j/jcl-over-slf4j "1.7.7"]
                             [org.slf4j/log4j-over-slf4j "1.7.7"]]
              :repositories
              [["sonatype-oss"
                "https://oss.sonatype.org/content/groups/public/"]]}})
