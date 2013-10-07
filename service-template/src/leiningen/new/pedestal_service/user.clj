(require 'clojure.stacktrace)

;; Attempt to load service-tools.dev namespace, catch any exceptions
;; so that REPL does not fail to start
(defonce service-tools-initialized
  (try
    (use 'io.pedestal.service-tools.dev)
    (require '{{namespace}}.service)
    (eval '(init {:service {{namespace}}.service/service
                  :routes-var #'{{namespace}}.service/routes}))
    true
    (catch Throwable t
      (println "ERROR: There was a problem loading io.pedestal.service-tools.dev\n")
      (clojure.stacktrace/print-stack-trace t)
      (println))))
