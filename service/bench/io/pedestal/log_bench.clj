; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.log-bench
  (:require io.pedestal.service.log
            clojure.tools.logging
            [criterium.core :as criterium])
  (:import (org.slf4j LoggerFactory)))

(defn- slf4j-pr-random []
  (let [n (rand-int 10)
        logger (LoggerFactory/getLogger "pedestal.log-bench")]
    (case n
      (0 1 2) (when (.isTraceEnabled logger)
                (.trace logger (pr-str {:n n :msg "TRACE is disabled"
                                        :rand (rand-int 1000)})))
      (3 4 5) (when (.isDebugEnabled logger)
                (.debug logger (pr-str {:n n :msg "DEBUG message"
                                        :rand (rand-int 1000)})))
      (6 7) (when (.isInfoEnabled logger)
              (.info logger (pr-str {:n n :msg "INFO message"
                                     :rand (rand-int 1000)})))
      8 (when (.isWarnEnabled logger)
          (.warn logger 
                 (pr-str {:n n :msg "WARN message"})
                 (ex-info "error message"
                          {:rand (rand-int 1000)})))
      9 (when (.isErrorEnabled logger)
          (.error logger
                  (pr-str {:n n :msg "ERROR message"})
                  (ex-info "error message"
                           {:rand (rand-int 1000)}))))))

(defn- platform-random []
  (let [n (rand-int 10)]
    (case n
      (0 1 2) (io.pedestal.service.log/trace :n n :msg "TRACE is disabled"
                                     :rand (rand-int 1000))
      (3 4 5) (io.pedestal.service.log/debug :n n :msg "DEBUG message"
                                     :rand (rand-int 1000))
      (6 7) (io.pedestal.service.log/info :n n :msg "INFO message"
                                  :rand (rand-int 1000))
      8 (io.pedestal.service.log/warn :n n :msg "WARN message"
                              :exception (ex-info "error message"
                                                  {:rand (rand-int 1000)}))
      9 (io.pedestal.service.log/error :n n :msg "ERROR message"
                               :exception (ex-info "error message"
                                                   {:rand (rand-int 1000)})))))

(defn- tools-logging-random []
  (let [n (rand-int 10)]
    (case n
      (0 1 2) (clojure.tools.logging/trace {:n n :msg "TRACE is disabled"
                                            :rand (rand-int 1000)})
      (3 4 5) (clojure.tools.logging/debug {:n n :msg "DEBUG message"
                                            :rand (rand-int 1000)})
      (6 7) (clojure.tools.logging/info {:n n :msg "INFO message"
                                         :rand (rand-int 1000)})
      8 (clojure.tools.logging/warn (ex-info "error message"
                                             {:rand (rand-int 1000)})
                                    {:n n :msg "WARN message"})
      9 (clojure.tools.logging/error (ex-info "error message"
                                              {:rand (rand-int 1000)})
                                     {:n n :msg "ERROR message"}))))

(defn quick-bench-slf4j-pr
  "Runs a short benchmark of SLF4J with pr-str, prints results."
  []
  (criterium/with-progress-reporting
    (criterium/quick-bench (slf4j-pr-random) :verbose)))

(defn quick-bench-platform
  "Runs a short benchmark of pedestal.log, prints results."
  []
  (criterium/with-progress-reporting
    (criterium/quick-bench (platform-random) :verbose)))

(defn quick-bench-tools-logging
  "Runs a short benchmark of clojure.tools.logging, prints results."
  []
  (criterium/with-progress-reporting
    (criterium/quick-bench (tools-logging-random) :verbose)))

(defn bench-slf4j-pr
  "Runs a long (1-2 minutes) benchmark of SLF4J with pr-str, prints
  results."
  []
  (criterium/with-progress-reporting
    (criterium/bench (slf4j-pr-random) :verbose)))

(defn bench-platform
  "Runs a long (1-2 minutes) benchmark of io.pedestal.service.log, prints
  results."
  []
  (criterium/with-progress-reporting
    (criterium/bench (platform-random) :verbose)))

(defn bench-tools-logging
  "Runs a long (1-2 minutes) benchmark of clojure.tools.logging, prints
  results."
  []
  (criterium/with-progress-reporting
    (criterium/bench (tools-logging-random) :verbose)))

(defn profile-platform
  "Runs a long loop (10 x 20000 iterations) of io.pedestal.service.log, for use
  with a profiler."
  []
  (dotimes [j 10]
    (dotimes [i 20000]
      (platform-random))))

(defn -main
  "Runs a benchmark of io.pedestal.service.log, clojure.tools.logging, and
  SLF4J. Prints results. Takes 4-6 minutes."
  []
  (println "
============================================================
io.pedestal.service.log
")
  (bench-platform)
  (println "
============================================================
clojure.tools.logging
")
  (bench-tools-logging)
  (println "
============================================================
SLF4J with pr-str
")
  (bench-slf4j-pr))
