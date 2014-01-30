---
Title: Hello World Service
---

<!--
 Copyright 2013 Relevance, Inc.
 Copyright 2014 Cognitect, Inc.

 The use and distribution terms for this software are covered by the
 Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
 which can be found in the file epl-v10.html at the root of this distribution.

 By using this software in any fashion, you are agreeing to be bound by
 the terms of this license.

 You must not remove this notice, or any other, from this software.
-->

# Hello World Service

This explains how to create a simple "Hello World" service on
Pedestal. This is the simplest ever service. As you can easily guess,
the service just responds with "Hello, World!" whenever you need a
friendly greeting.

## Create a Clojure project for the server side

```
mkdir ~/tmp
cd ~/tmp
lein new pedestal-service helloworld
cd helloworld
```

## Edit project.clj

The generated project definition looks like this:

```clojure
(defproject helloworld "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [io.pedestal/pedestal.service "0.2.2"]
                 [io.pedestal/pedestal.service-tools "0.2.2"]

                 ;; Remove this line and uncomment the next line to
                 ;; use Tomcat instead of Jetty:
                 [io.pedestal/pedestal.jetty "0.2.2"]
                 ;; [io.pedestal/pedestal.tomcat "0.2.2"]
                 ]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :aliases {"run-dev" ["trampoline" "run" "-m" "helloworld.server/run-dev"]}
  :repl-options  {:init-ns user
                  :init (try
                          (use 'io.pedestal.service-tools.dev)
                          (require 'helloworld.service)
                          ;; Nasty trick to get around being unable to reference non-clojure.core symbols in :init
                          (eval '(init helloworld.service/service #'helloworld.service/routes))
                          (catch Throwable t
                            (println "ERROR: There was a problem loading io.pedestal.service-tools.dev")
                            (clojure.stacktrace/print-stack-trace t)
                            (println)))
                  :welcome (println "Welcome to pedestal-service! Run (tools-help) to see a list of useful functions.")}
  :main ^{:skip-aot true} helloworld.server)
```

You may want to change the description, add dependencies, change the
license, or whatever else you'd normally do to project.clj. Once you
finish editing the file, run `lein deps` to fetch any jars you need.

## Edit service.clj

Our project name is helloworld, so the template generated two files
under `src/helloworld`:

1. `service.clj` defines the logic of our service.
2. `server.clj` creates a server (a daemon) to host that service.

Of course, if you used a different project name, your service.clj
would be src/your-project-name-here/service.clj. Also, the namespace
will be your-project-name-here.service instead of `helloworld.service`.

The default service.clj demonstrates a few things, but for now let's
replace the default service.clj with the smallest example that will
work. Edit src/helloworld/service.clj until it looks like this:

```clojure
(ns helloworld.service
    (:require [io.pedestal.service.http :as bootstrap]
              [io.pedestal.service.http.route :as route]
              [io.pedestal.service.http.body-params :as body-params]
              [io.pedestal.service.http.route.definition :refer [defroutes]]
              [ring.util.response :as ring-resp]))

(defn home-page
  [request]
  (ring-resp/response "Hello World!"))

(defroutes routes
  [[["/" {:get home-page}
        ;; Set default interceptors for any other paths under /
        ^:interceptors [(body-params/body-params) bootstrap/html-body]]]])

;; Consumed by helloworld.server/create-server
(def service {:env :prod
              ::bootstrap/routes routes
              ::bootstrap/resource-path "/public"
              ::bootstrap/type :jetty
              ::bootstrap/port 8080})
```

The `home-page` function defines the simplest HTTP response to the
browser. In `routes`, we map the URL `/` so it will invoke
`home-page`. Finally, the var `service` describes how to hook
this up to a server. Notice that this is just a map. `service`
doesn't actually start the server up; it defines how the service will
look when it gets started later.

There's nothing magic about these function names. There are no
required names here. One of our design principles in Pedestal is that
all the connections between parts of your application should be
_evident_. You should be able to trace functions from call to
definition without any "magic" or "action at a distance"
metaprogramming.

Take a peek into `src/helloworld/server.clj`. We won't be changing it,
but it's interesting to look at the main function:

``` clojure
(ns helloworld.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.service-tools.server :as server]
            [helloworld.service :as service]
            [io.pedestal.service-tools.dev :as dev]))

;; ...

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (server/init service/service)
  (apply server/-main args))

;; ...

```

You can see that `io.pedestal.service-tools.server/init` is invoked with
`helloworld.service/service`--the map we just looked at--which is used to
create the actual server.


## Run it in Dev Mode

We'll start the server from a repl, which is how we will normally run in development mode.

```bash
$ lein repl

nREPL server started on port 60617 on host 127.0.0.1
REPL-y 0.3.0
Clojure 1.5.1
Welcome to pedestal-service! Run (tools-help) to see a list of useful functions.
```

To make life easier in the repl, pedestal has some convenience functions:

```clojure
user=> (tools-help)

Start a new service development server with (start) or (start service-options)
----
Type (start) or (start service-options) to initialize and start a server
Type (stop) to stop the current server
Type (restart) to restart the current server
----
Type (watch) to watch for changes in the src/ directory

nil
```

We'll use one to start the server:

```clojure
user=> (start)
INFO  org.eclipse.jetty.server.Server - jetty-8.1.9.v20130131
INFO  o.e.jetty.server.AbstractConnector - Started SelectChannelConnector@0.0.0.0:8080
nil
```

Now let's see "Hello World!"

Go to [http://localhost:8080/](http://localhost:8080/)  and you'll see a shiny "Hello World!" in your browser.

Done! Let's stop the server.

```clojure
user=> (stop)
nil
```

## Where To Go Next

For more about building out the server side, you can look at
[Routing and Linking](/documentation/service-routing/) or
[Connecting to Datomic](/documentation/connecting-to-datomic/).

