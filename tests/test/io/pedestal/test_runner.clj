(ns io.pedestal.test-runner
  (:require [cognitect.test-runner.api :as api])
  (:refer-clojure :exclude [test])
  (:import (java.lang ProcessHandle)))

(defn test
  "Wrapper around normal test-runner that shuts agents down on completion."
  [options]
  (api/test options)
  ;; Process hangs after completion; if we get this far, kill the process.
  (System/exit 0))
