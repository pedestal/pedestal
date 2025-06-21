(ns org.example.sse.telemetry-test-init)

(defn metric-source
  []
  ;; Disable metrics when running tests.
  nil)

(defn tracing-source
  []
  ;; Disable tracing when running tests.
  nil)
