(defproject {{raw-name}} "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [org.clojure/clojurescript "0.0-1586"]
                 [domina "1.0.1"]
                 [ch.qos.logback/logback-classic "1.0.7" :exclusions [org.slf4j/slf4j-api]]
                 [io.pedestal/pedestal.app "0.1.8-SNAPSHOT"]
                 [io.pedestal/pedestal.app-tools "0.1.8-SNAPSHOT"]]
  :profiles {:dev {:source-paths ["dev"]}}
  :min-lein-version "2.0.0"
  :source-paths ["app/src" "app/templates"]
  :resource-paths ["config"]
  :target-path "out/"
  :aliases {"dumbrepl" ["trampoline" "run" "-m" "clojure.main/main"]})
