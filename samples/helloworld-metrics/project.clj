(defproject helloworld-metrics "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [io.pedestal/pedestal.service "0.4.2-SNAPSHOT"]

                 ;; Remove this line and uncomment one of the next lines to
                 ;; use Immutant or Tomcat instead of Jetty:
                 [io.pedestal/pedestal.jetty "0.4.2-SNAPSHOT"]
                 ;; [io.pedestal/pedestal.immutant "0.4.2-SNAPSHOT"]
                 ;; [io.pedestal/pedestal.tomcat "0.4.2-SNAPSHOT"]

                 [ch.qos.logback/logback-classic "1.1.3" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.13"]
                 [org.slf4j/jcl-over-slf4j "1.7.13"]
                 [org.slf4j/log4j-over-slf4j "1.7.13"]
                 [com.readytalk/metrics3-statsd "4.1.0"]]
  :repositories [["jcenter" "http://jcenter.bintray.com"]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "helloworld-metrics.server/run-dev"]}}
             :uberjar {:aot [helloworld-metrics.server]}}
  :main ^{:skip-aot true} helloworld-metrics.server)

