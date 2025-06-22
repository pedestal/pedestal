(ns user
  (:require [clj-commons.pretty.repl :as repl]
            matcher-combinators.test
            [clojure.pprint :refer [pprint]]
            [net.lewisship.trace :as trace]
            [io.pedestal.environment :refer [dev-mode?]]))

(alter-var-root #'*warn-on-reflection* (constantly true))

(repl/install-pretty-exceptions)
(trace/setup-default)

(trace/trace :dev-mode? dev-mode?)

(trace/set-enable-trace! false)

(comment


  (trace/set-enable-trace! true)
  (trace/set-compile-trace! false)

  (add-tap pprint)
  (remove-tap pprint)
  )

