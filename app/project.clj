(defproject io.pedestal/pedestal.app "0.0.9-SNAPSHOT"
  :description "Pedestal applications"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [org.clojure/clojurescript "0.0-1450"]
                 [ch.qos.logback/logback-classic "1.0.6"]
                 [enlive "1.0.0" :exclusions [org.clojure/clojure]]
                 [domina "1.0.1"]]
  :profiles {:dev {:source-paths ["dev"]}}
  :aliases {"dumbrepl" ["trampoline" "run" "-m" "clojure.main/main"]})
