(require 'clojure.stacktrace)

(defn dev
  []
  (try
    (require 'dev)
    (in-ns 'dev)
    (println "Run (tools-help) to see a list of useful functions.")
    :ok
    (catch Throwable t
      (println "ERROR: There was a problem loading io.pedestal.service-tools.dev\n")
      (clojure.stacktrace/print-cause-trace t)
      (println))))



