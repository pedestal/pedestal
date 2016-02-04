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
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [io.pedestal/pedestal.service "0.4.1"]

                 ;; Remove this line and uncomment one of the next lines to
                 ;; use Immutant or Tomcat instead of Jetty:
                 [io.pedestal/pedestal.jetty "0.4.1"]
                 ;; [io.pedestal/pedestal.immutant "0.4.1"]
                 ;; [io.pedestal/pedestal.tomcat "0.4.1"]

                 [ch.qos.logback/logback-classic "1.1.2" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.7"]
                 [org.slf4j/jcl-over-slf4j "1.7.7"]
                 [org.slf4j/log4j-over-slf4j "1.7.7"]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "helloworld.server/run-dev"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.4.1"]]}
             :uberjar {:aot [helloworld.server]}}
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
    (:require [io.pedestal.http :as bootstrap]
              [io.pedestal.http.route :as route]
              [io.pedestal.http.body-params :as body-params]
              [io.pedestal.http.route.definition :refer [defroutes]]
              [ring.util.response :as ring-resp]))

(defn home-page
  [request]
  (ring-resp/response "Hello World!"))

(defroutes routes
  [[["/" {:get home-page}]]])

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
  (:require [io.pedestal.http :as server]
            [helloworld.service :as service]))

;; ...

(defonce runnable-service (server/create-server service/service))

;; ...

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (println "\nCreating your server...")
  (server/start runnable-service))

```

Here we call `io.pedestal.http/create-server` to create a web server
configured by `helloworld.service/service`, then start it with
`io.pedestal.http/start`.

## Run it in Dev Mode

We'll start the server from a repl, which is how we will normally run in development mode.

```bash
INFO  org.eclipse.jetty.util.log - Logging initialized @10952ms
nREPL server started on port 57657 on host 127.0.0.1 - nrepl://127.0.0.1:57657
REPL-y 0.3.7, nREPL 0.2.10
Clojure 1.6.0
Java HotSpot(TM) 64-Bit Server VM 1.8.0_25-b17
   Docs: (doc function-name-here)
         (find-doc "part-of-name-here")
 Source: (source function-name-here)
Javadoc: (javadoc java-object-or-class-here)
   Exit: Control+D or (exit) or (quit)
Results: Stored in vars *1, *2, *3, an exception in *e

helloworld.server=>
```

To start a server, we call io.pedestal.http/start with our configuration map:

```clojure
helloworld.server=> (server/start runnable-service)
INFO  org.eclipse.jetty.server.Server - jetty-9.2.0.v20140526
INFO  o.e.j.server.handler.ContextHandler - Started o.e.j.s.ServletContextHandler@40ca2376{/,null,AVAILABLE}
INFO  o.e.jetty.server.ServerConnector - Started ServerConnector@161f5c2e{HTTP/1.1}{0.0.0.0:8080}
INFO  org.eclipse.jetty.server.Server - Started @18149ms
```

Then hit Ctrl-C to get back to the REPL prompt.

Now let's see "Hello World!"

Go to [http://localhost:8080/](http://localhost:8080/)  and you'll see a shiny "Hello World!" in your browser.

Done! Let's stop the server and exit the REPL.

```clojure
helloworld.server=> (server/stop runnable-service)

(Lengthy string representation of the server is printed here.)

helloworld.server=> (quit)
Bye for now!
$
```

## Where To Go Next

For more about building out the server side, you can look at
[Routing and Linking](service-routing.md) or
[Connecting to Datomic](connecting-to-datomic.md).
