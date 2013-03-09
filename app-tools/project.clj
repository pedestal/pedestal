(defproject io.pedestal/pedestal.app-tools "0.0.9-SNAPSHOT"
  :description "Pedestal tools for application development"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [org.clojure/clojurescript "0.0-1450"]
                 [org.clojure/tools.namespace "0.2.1"]
                 [org.clojure/java.classpath "0.2.0"]
                 [ch.qos.logback/logback-classic "1.0.7"]
                 [org.slf4j/jul-to-slf4j "1.7.2"]
                 [org.slf4j/jcl-over-slf4j "1.7.2"]
                 [org.slf4j/log4j-over-slf4j "1.7.2"]
                 [io.pedestal/pedestal.app "0.0.9-SNAPSHOT"]
                 [io.pedestal/pedestal.service "0.0.1-SNAPSHOT"]
                 [io.pedestal/pedestal.jetty "0.0.1-SNAPSHOT"]
                 [enlive "1.0.0" :exclusions [org.clojure/clojure]]
                 [domina "1.0.1"]
                 [clj-http "0.5.5"]]
  :aliases {"dumbrepl" ["trampoline" "run" "-m" "clojure.main/main"]})
