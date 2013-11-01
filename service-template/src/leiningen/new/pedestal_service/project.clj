(defproject {{raw-name}} "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [io.pedestal/pedestal.service "0.2.2-SNAPSHOT"]

                 ;; Remove this line and uncomment the next line to
                 ;; use Tomcat instead of Jetty:
                 [io.pedestal/pedestal.jetty "0.2.2-SNAPSHOT"]
                 ;; [io.pedestal/pedestal.tomcat "0.2.2-SNAPSHOT"]
                 ]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :profiles {:dev {:resource-paths ["dev"]
                   :aliases {"run-dev" ["trampoline" "run" "-m" "dev/-main"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.2.2-SNAPSHOT"]]
                   :repl-options  {:init-ns user
                                   :welcome (println "Welcome to pedestal-service! Run (dev) to load tools.")}}}
  :main ^{:skip-aot true} {{namespace}}.server)
