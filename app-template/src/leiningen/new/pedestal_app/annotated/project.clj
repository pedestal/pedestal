(defproject {{name}} "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [org.clojure/clojurescript "0.0-1586"]
                 [domina "1.0.1"]
                 [ch.qos.logback/logback-classic "1.0.6"]
                 [io.pedestal/pedestal.app "0.1.0-SNAPSHOT"]
                 [io.pedestal/pedestal.app-tools "0.1.0-SNAPSHOT"]]
  :profiles {:dev {:source-paths ["dev"]}}
  :source-paths ["app/src" "app/templates"]
  :resource-paths ["config"]
  :aliases {"dumbrepl" ["trampoline" "run" "-m" "clojure.main/main"]})
