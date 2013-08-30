(defproject {{raw-name}} "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [io.pedestal/pedestal.service "0.2.0-SNAPSHOT"]
                 [io.pedestal/pedestal.service-tools "0.2.0-SNAPSHOT"]

                 ;; Remove this line and uncomment the next line to
                 ;; use Tomcat instead of Jetty:
                 [io.pedestal/pedestal.jetty "0.2.0-SNAPSHOT"]
                 ;; [io.pedestal/pedestal.tomcat "0.2.0-SNAPSHOT"]
                 ]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :aliases {"run-dev" ["trampoline" "run" "-m" "{{namespace}}.server/run-dev"]}
  :repl-options  {:init-ns user
                  :init (try
                          (use 'io.pedestal.service-tools.dev)
                          (require '{{namespace}}.service)
                          ;; TODO: review with @timewald
                          ;; Nasty trick to resolve non-clojure.core symbols in :init. Equivalent to:
                          ;; (io.pedestal.service-tools.dev/init {{namespace}}.service/service #'{{namespace}}.service/routes)
                          (@(resolve (symbol "io.pedestal.service-tools.dev" "init"))
                                     @(resolve (symbol "{{namespace}}.service" "service")) (resolve (symbol "{{namespace}}.service" "routes")))
                          (catch Throwable t
                            (println "ERROR: There was a problem loading io.pedestal.service-tools.dev")
                            (clojure.stacktrace/print-stack-trace t)
                            (println)))
                  :welcome (println "Welcome to pedestal-service! Run (tools-help) to see a list of useful functions.")}
  :main ^{:skip-aot true} {{namespace}}.server)
