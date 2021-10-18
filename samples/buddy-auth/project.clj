(defproject buddy-auth "0.5.9"
  :description "A Pedestal service demonstrating Buddy Auth integration."
  :url "http://pedestal.io/samples/index"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [io.pedestal/pedestal.service "0.5.9"]

                 [io.pedestal/pedestal.jetty "0.5.9"]

                 [ch.qos.logback/logback-classic "1.2.6" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.32"]
                 [org.slf4j/jcl-over-slf4j "1.7.32"]
                 [org.slf4j/log4j-over-slf4j "1.7.32"]

                 [buddy/buddy-auth "3.0.1" :exclusions [cheshire]]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :profiles {:dev     {:aliases      {"run-dev" ["trampoline" "run" "-m" "buddy-auth.server/run-dev"]}
                       :dependencies [[io.pedestal/pedestal.service-tools "0.5.9"]]}
             :uberjar {:aot [buddy-auth.server]}}
  :main ^{:skip-aot true} buddy-auth.server)
