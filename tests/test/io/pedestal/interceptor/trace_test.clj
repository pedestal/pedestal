; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor.trace-test
  (:require [io.pedestal.interceptor.trace :as trace]
            [clojure.test :refer [deftest is]])
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
