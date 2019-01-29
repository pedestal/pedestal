(defproject buddy-auth "0.5.5"
  :description "A Pedestal service demonstrating Buddy Auth integration."
  :url "http://pedestal.io/samples/index"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [io.pedestal/pedestal.service "0.5.5"]

                 [io.pedestal/pedestal.jetty "0.5.5"]

                 [ch.qos.logback/logback-classic "1.2.3" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]

                 [buddy/buddy-auth "2.1.0" :exclusions [cheshire]]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :profiles {:dev     {:aliases      {"run-dev" ["trampoline" "run" "-m" "buddy-auth.server/run-dev"]}
                       :dependencies [[io.pedestal/pedestal.service-tools "0.5.5"]]}
             :uberjar {:aot [buddy-auth.server]}}
  :main ^{:skip-aot true} buddy-auth.server)
