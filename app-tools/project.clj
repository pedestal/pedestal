; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(defproject io.pedestal/pedestal.app-tools "0.1.11-SNAPSHOT"
  :description "Pedestal tools for application development"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-1835"]
                 [org.clojure/tools.namespace "0.2.1"]
                 [org.clojure/java.classpath "0.2.0"]
                 [ch.qos.logback/logback-classic "1.0.7" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.2"]
                 [org.slf4j/jcl-over-slf4j "1.7.2"]
                 [org.slf4j/log4j-over-slf4j "1.7.2"]
                 [io.pedestal/pedestal.app "0.1.11-SNAPSHOT"]
                 [io.pedestal/pedestal.service "0.1.11-SNAPSHOT"]
                 [io.pedestal/pedestal.jetty "0.1.11-SNAPSHOT"]
                 [enlive "1.0.0" :exclusions [org.clojure/clojure]]
                 [domina "1.0.1"]
                 [com.cemerick/piggieback "0.0.4"]]
  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]})
