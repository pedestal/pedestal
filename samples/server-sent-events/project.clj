(defproject server-sent-events "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 ;; change to 0.3.2 after next release
                 [io.pedestal/pedestal.service "0.3.2-SNAPSHOT"]

                 ;; Remove this line and uncomment the next line to
                 ;; use Tomcat instead of Jetty:
                 [io.pedestal/pedestal.jetty "0.3.1"]
                 ;; [io.pedestal/pedestal.tomcat "0.1.2"]

                 ;; Logging
                 [ch.qos.logback/logback-classic "1.1.2" :exclusions [[org.slf4j/slf4j-api]]]
                 [org.slf4j/jul-to-slf4j "1.7.7"]
                 [org.slf4j/jcl-over-slf4j "1.7.7"]
                 [org.slf4j/log4j-over-slf4j "1.7.7"]

                 ;; Example CLJS client
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/clojurescript "0.0-2371"]]
  :plugins [[lein-cljsbuild "1.0.3"]]
  :profiles {:dev {:source-paths ["dev"]}}
  :min-lein-version "2.0.0"
  :resource-paths ["resources" "config"]
  :global-vars  {*warn-on-reflection* true
                 *assert* true}
  :pedantic? :abort
  :main ^{:skip-aot true} server-sent-events.server
  :cljsbuild {:builds [{:source-paths  ["src" "target/classes"]
                        :compiler {:output-dir "target/out"
                                   :output-to "resources/public/js/app.js"
                                   :pretty-print false
                                   :optimizations :advanced}}]})

