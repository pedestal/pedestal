(require 'clojure.stacktrace)

(defn dev
  []
  (try
    (use 'io.pedestal.service-tools.dev)
    (require '{{namespace}}.service :reload-all)
    (eval '(init {:service {{namespace}}.service/service
                  :routes-var #'{{namespace}}.service/routes}))
    :ok
    (catch Throwable t
      (println "ERROR: There was a problem loading io.pedestal.service-tools.dev\n")
      (clojure.stacktrace/print-cause-trace t)
      (println))))

;; (dev)

