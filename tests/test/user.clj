(ns user
  (:require [clj-commons.pretty.repl :as repl]
            matcher-combinators.test
            [net.lewisship.trace :as trace]
            [io.pedestal.environment :refer [dev-mode?]]))

(set! *warn-on-reflection* true)

(repl/install-pretty-exceptions)
(trace/setup-default)

(trace/trace :dev-mode? dev-mode?)

(trace/set-enable-trace! false)

(comment


  (trace/set-enable-trace! true)
  (trace/set-compile-trace! false)
  )

