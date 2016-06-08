# Pedestal Service

Core components for building Pedestal Services.

## Install

Add this to the `:dependencies` vector of your `project.clj`:

    [io.pedestal/pedestal.service "LATEST_VERSION"]

See [our releases](https://github.com/pedestal/pedestal/releases) for the LATEST\_VERSION number.


## Developer Notes

### Benchmarks

    lein bench-log

This will run 1-2 minute benchmarks comparing com.clojure.log with
clojure.tools.logging.

Benchmarking code is in the `bench` directory and includes a Logback
configuration file.

<!-- Copyright 2013 Relevance, Inc. -->
<!-- Copyright 2014-2016 Cognitect, Inc. -->
