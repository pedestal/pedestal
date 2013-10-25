(require 'clojure.stacktrace)

;; Attempt to load app-tools.dev namespace, catch any exceptions so that REPL
;; does not fail to start
(defonce app-tools-initialized
  (try
    (use 'io.pedestal.app-tools.dev)
    true
    (catch Throwable t
      (println "ERROR: There was a problem loading io.pedestal.app-tools.dev")
      (clojure.stacktrace/print-stack-trace t)
      (println))))
