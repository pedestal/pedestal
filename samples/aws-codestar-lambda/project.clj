(defproject pedestal-lambda "0.5.9"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.3"]

                 [io.pedestal/pedestal.service "0.5.9"]
                 [io.pedestal/pedestal.jetty "0.5.9"]
                 [io.pedestal/pedestal.aws "0.5.9" :exclusions [joda-time]]

                 [ch.qos.logback/logback-classic "1.2.6" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.32"]
                 [org.slf4j/jcl-over-slf4j "1.7.32"]
                 [org.slf4j/log4j-over-slf4j "1.7.32"]

                 ;; Deps cleanup
                 [joda-time "2.10.12"]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  ;; If you use HTTP/2 or ALPN, use the java-agent to pull in the correct alpn-boot dependency
  ;;:java-agents [[org.mortbay.jetty.alpn/jetty-alpn-agent "2.0.10"]]
  :pedantic? :abort
  :global-vars {*warn-on-reflection* true
                *unchecked-math* :warn-on-boxed
                *assert* true}
  :profiles {:srepl {:jvm-opts ^:replace ["-XX:+UseG1GC"
                                          "-Dclojure.server.repl={:port 5555 :accept clojure.core.server/repl}"]}
             :dev {:aliases {"crepl" ["trampoline" "run" "-m" "clojure.main/main"]
                             "srepl" ["with-profile" "srepl" "trampoline" "run" "-m" "clojure.main/main"]
                             "run-dev" ["trampoline" "run" "-m" "pedestallambda.server/run-dev"]}
                   :resource-paths ["config" "resources" "test/resources"]
                   :dependencies [[io.pedestal/pedestal.service-tools "0.5.9"]]}
             :uberjar {:jvm-opts ["-Dclojure.compiler.direct-linking=true"]
                       :aot [pedestallambda.server]}}
  :main ^{:skip-aot true} pedestallambda.server)
