(defproject tracing "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [io.pedestal/pedestal.service "0.5.4-SNAPSHOT"]

                 ;; Remove this line and uncomment one of the next lines to
                 ;; use Immutant or Tomcat instead of Jetty:
                 [io.pedestal/pedestal.jetty "0.5.4-SNAPSHOT"]
                 ;; [io.pedestal/pedestal.immutant "0.5.4-SNAPSHOT"]
                 ;; [io.pedestal/pedestal.tomcat "0.5.4-SNAPSHOT"]

                 [ch.qos.logback/logback-classic "1.2.3" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]

                 ;; Tracing backend
                 [io.jaegertracing/jaeger-core "0.27.0"]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :pedantic? :abort
  ;; If you use HTTP/2 or ALPN, use the java-agent to pull in the correct alpn-boot dependency
  ;:java-agents [[org.mortbay.jetty.alpn/jetty-alpn-agent "2.0.5"]]
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "tracing.server/run-dev"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.5.4-SNAPSHOT"]]}
             :uberjar {:aot [tracing.server]}}
  :main ^{:skip-aot true} tracing.server)

