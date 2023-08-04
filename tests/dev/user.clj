(ns user
  (:require [clj-commons.pretty.repl :as repl]))

(repl/install-pretty-exceptions)

(set! *warn-on-reflection* true)
