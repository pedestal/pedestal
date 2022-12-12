(ns io.pedestal.interceptor.trace-test
  (:require [io.pedestal.interceptor.trace :as trace]
            [clojure.test :refer :all])
  (:import (io.jaegertracing Configuration
                             Configuration$SamplerConfiguration
                             Configuration$ReporterConfiguration
                             Configuration$SenderConfiguration)))

(def jaeger-tracer
   (.getTracer
    (-> (Configuration/fromEnv "test-service")
        (.withSampler (-> (Configuration$SamplerConfiguration/fromEnv)
                          (.withType "const")
                          (.withParam 1)))
        (.withReporter (-> (Configuration$ReporterConfiguration/fromEnv)
                           (.withLogSpans false)
                           (.withFlushInterval (int 1000))
                           (.withMaxQueueSize (int 10000))
                           (.withSender (-> (Configuration$SenderConfiguration.)
                                            (.withAgentHost "localhost")
                                            (.withAgentPort (int 5775)))))))))

(deftest extract-span-context
  (is (not (nil? (trace/headers->span-context
                  jaeger-tracer
                  {"uber-trace-id" "c7f80b90c5049e2b:28cbcfdd0ed5e4a9:c7f80b90c5049e1b:1"})))))
