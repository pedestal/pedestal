(defproject {{raw-name}} "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [io.pedestal/pedestal.service "0.5.3-SNAPSHOT"]

                 ;; Remove this line and uncomment one of the next lines to
                 ;; use Immutant or Tomcat instead of Jetty:
                 [io.pedestal/pedestal.jetty "0.5.3-SNAPSHOT"]
                 ;; [io.pedestal/pedestal.immutant "0.5.3-SNAPSHOT"]
                 ;; [io.pedestal/pedestal.tomcat "0.5.3-SNAPSHOT"]

                 [ch.qos.logback/logback-classic "1.1.8" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.22"]
                 [org.slf4j/jcl-over-slf4j "1.7.22"]
                 [org.slf4j/log4j-over-slf4j "1.7.22"]
                 [environ "1.1.0"]]

  :min-lein-version "2.0.0"
  :plugins [[lein-environ "1.1.0"]]
  :hooks [lein-environ.plugin/hooks]
  :resource-paths ["config", "resources"]
  ;; If you use HTTP/2 or ALPN, use the java-agent to pull in the correct alpn-boot dependency
  ;:java-agents [[org.mortbay.jetty.alpn/jetty-alpn-agent "2.0.5"]]
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "{{namespace}}.server/run-dev"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.5.3-SNAPSHOT"]]}
             :uberjar {:aot [{{namespace}}.server]}}
  :main ^{:skip-aot true} {{namespace}}.server)

