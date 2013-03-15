# Copyright 2013 Relevance, Inc.

Pedestal.Server
========================================

Developer Bootstrap
--------------------

You will need:

* Leiningen 2


Docs
--------------------
Generate docs with the installed lein plugin for marginalia.

    lein marg

This will generate literate-programming-style documentation for all
source files in ./src and place them in ./docs.


Benchmarks
--------------------

    lein bench-log

This will run 1-2 minute benchmarks comparing com.clojure.log with
clojure.tools.logging.

Benchmarking code is in the `bench` directory and includes a Logback
configuration file.
