(defproject tracing "0.5.9"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [io.pedestal/pedestal.service "0.5.9"]

                 ;; Remove this line and uncomment one of the next lines to
                 ;; use Tomcat instead of Jetty:
                 [io.pedestal/pedestal.jetty "0.5.9"]
                 ;; [io.pedestal/pedestal.tomcat "0.5.9"]

                 [ch.qos.logback/logback-classic "1.2.10" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.35"]
                 [org.slf4j/jcl-over-slf4j "1.7.35"]
                 [org.slf4j/log4j-over-slf4j "1.7.35"]

                 ;; Tracing backend
                 [io.jaegertracing/jaeger-core "1.6.0"]
                 ;; Thrift senders were extracted in version 0.30.0 of jaeger-client-java.
                 ;; See https://github.com/jaegertracing/jaeger-client-java/blob/master/CHANGELOG.md#0300-2018-07-04
                 [io.jaegertracing/jaeger-thrift "1.6.0"
                  :exclusions [org.jetbrains.kotlin/kotlin-stdlib-common]]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :pedantic? :abort
  ;; If you use HTTP/2 or ALPN, use the java-agent to pull in the correct alpn-boot dependency
                                        ;:java-agents [[org.mortbay.jetty.alpn/jetty-alpn-agent "2.0.5"]]
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "tracing.server/run-dev"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.5.9"]]}
             :uberjar {:aot [tracing.server]}}
  :main ^{:skip-aot true} tracing.server)
