(defproject jetty-web-sockets "0.5.1"
  :description "Sample of web sockets with Jetty"
  :url "http://pedestal.io/samples/index"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [io.pedestal/pedestal.service "0.5.1"]
                 [org.clojure/core.async "0.2.391"]

                 ;; Remove this line and uncomment one of the next lines to
                 ;; use Immutant or Tomcat instead of Jetty:
                 [io.pedestal/pedestal.jetty "0.5.1"]
                 ;; [io.pedestal/pedestal.immutant "0.5.1"]
                 ;; [io.pedestal/pedestal.tomcat "0.5.1"]

                 [ch.qos.logback/logback-classic "1.1.7" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.21"]
                 [org.slf4j/jcl-over-slf4j "1.7.21"]
                 [org.slf4j/log4j-over-slf4j "1.7.21"]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :pedantic? :abort
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "jetty-web-sockets.server/run-dev"]}}
             :uberjar {:aot [jetty-web-sockets.server]}}
  :main ^{:skip-aot true} jetty-web-sockets.server)
