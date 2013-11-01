(defproject {{raw-name}} "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-1835"]
                 [domina "1.0.1"]
                 [ch.qos.logback/logback-classic "1.0.13" :exclusions [org.slf4j/slf4j-api]]
                 [io.pedestal/pedestal.app "0.2.2-SNAPSHOT"]
                 [com.cemerick/piggieback "0.1.0"]]
  :min-lein-version "2.0.0"
  :source-paths ["app/src" "app/templates"]
  :resource-paths ["config"]
  :target-path "out/"
  :profiles {:dev {:resource-paths ["dev"]
                   :dependencies [[io.pedestal/pedestal.app-tools "0.2.2-SNAPSHOT"]]
                   :repl-options {:init-ns user
                                  :welcome (println "Welcome to pedestal-app! Run (tools-help) to see a list of useful functions.")}}}
  :main ^{:skip-aot true} io.pedestal.app-tools.dev)


