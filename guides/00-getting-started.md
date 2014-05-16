---
title: Getting Started
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

# Getting Started

This getting started tutorial will guide you through:

* Adding Pedestal to a project,
* Creating basic routes & handlers,
* Defining a service configuration map, and
* Setting up a simple Pedestal web-server.

All told, it should take no more than 15 minutes. For a longer, more
substantive tutorial, consider following along with Ryan Neufeld's
[Web Apps on a Pedestal](http://www.oreilly.com/pub/e/3039), a workshop walks
through building a complete CRUD application with Pedestal.

## Adding Pedestal to a Project

The first thing you'll need to build a Pedestal application is a host
project. I say *host*, because a Pedestal application does not
constitute the whole of an application-much like a Ruby on Rails
application might. Rather, Pedestal is used from an existing
application or library as a means of building a web service. You can
certainly construct applications with Pedestal at a base, but it is
by no means necessary.

In this guide, we'll embed Pedestal into a fresh Clojure
project.

Create a fresh project of your own using [Leiningen](http://leiningen.org/#install):

```sh
$ lein new hello-pedestal
Generating a project called hello-pedestal based on the 'default'
template.
To see other templates (app, lein plugin, etc), try `lein help new`.
```

At this point, you will have a directory `hello-pedestal/` that is nothing
more than a plain Clojure application. Enter the directory, and take a
look at the generated `project.clj`:

```sh
$ cd hello-pedestal
$ cat project.clj
(defproject hello-pedestal "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]])
```

To add Pedestal to a project, you'll need to include a few dependencies:
the core service library, the tools library, and one of many possible server
adapters. For our simple service, we'll opt for the Jetty adapter.

Add Pedestal's dependencies to your `project.clj`'s `:dependencies`
key like so:

```clj
:dependencies [[org.clojure/clojure "1.5.1"]
               [io.pedestal/pedestal.service "0.3.0-SNAPSHOT"]
               [io.pedestal/pedestal.service-tools "0.3.0-SNAPSHOT"]
               [io.pedestal/pedestal.jetty "0.3.0-SNAPSHOT"]]
```

These three dependencies are sufficient for adding Pedestal to any
application.

## Saying Hello

There are three basic elements required to serve a basic web request with
Pedestal:

* Service - a description of the service to run, namely its routes.
* Routes - a mapping of URIs to handlers
* Handler - a function that receives a request as input, and returns a response.

This is the flow of a simple request:

1. Service receives a request (e.g. `GET /hello`)
2. Service chooses the first route that matches that request (e.g.
   `["/hello" {:get hello-world}]`)
3. Service invokes that route's handler with the request (e.g.
   `(hello-world {...})`)
4. Handler responds with a response. (e.g. `{:status 200, :body "Hello, World!", :headers {}}`)
5. Service sends response to requester.

Let's build the "hello" handler, route and service into our own application.

### Handler

While there are many interesting things you can do inside a handler, the most
basic is a function that returns a simple response map. Pedestal's requests and responses are [Ring-compliant](https://github.com/mmcgrana/ring/blob/master/SPEC), so "responding" is simply a matter of returning a map with they keys `:status`, `:body`, and `:headers`.

Add such near the top of `src/hello-pedestal/core.clj`

```clj
(defn hello-world [request]
  {:status 200
   :body "Hello, World!"
   :headers {}})
```

### Routes

Routing is the first place we actually introduce any Pedestal machinery.


Routes in Pedestal are defined using a simple DSL. The most basic route
consists of a **path**, and a **handler-map** (a map of HTTP verbs and handler
functions). The following route will invoke `hello-world` for any GET requests on the
"/hello" path:

```clj
["/hello" {:get hello-world}]
```

Optionally, a route can have any number of child routes. These follow the handler-map. Consider a fictitious route representing a source repositories: the path "/repos" would return an index of all repositories, while the "/repos/starred" route would only display starred repositories.

```clj
["/repos" {:get repos/index}
 ["/starred" {:get users/show}]]
```

To define a whole collection of routes--a routing table--you can use the `defroutes` macro. This macro expands the terse vector-based format you've just learned into a more machine-processable format.

Inside `core.clj`, add a set of routes for our hello-world application. You'll also need to refer `defroutes` from its namespace, `io.pedestal.http.route.definition`.

```clj
(ns hello-pedestal.core
  (:require [io.pedestal.http.route.definition :refer [defroutes]))

(defn hello-world [req]
  {:status 200
   :body "Hello, world!"
   :headers {}})

(defroutes routes
  [[["/"
     ["/hello" {:get hello-world}]]]])
```

*Note: `handler-map` is actually an optional value. Since the root ("/") in our application will do nothing, it does not have a handler map.*

### Service

* TODO

## Setting up a Web-server

For our application to do anything interesting--e.g. responding to web
requests--it needs to be able to actually run something. To this end,
we need to create a `-main` function that we can use to launch a
webserver when we run our application.

Inside `core.clj`:

```clj
;; ...

(defn -main [& args]
  ;; ?
  )
```

But what actually goes inside `-main`? We have a service map, but that
is just data. To start a server from this map, we need to use two
functions from `io.pedestal.http`: `create-server` and `start`.

The first function, `http/create-server`, transforms `service`, a map of
configuration for a server, into a fully runnable server with all of
the fixings; start functions, stop functions, a full set of verbose
routes, you name it-the server map returned by `create-server` is
everything there is to know about your application.

With that in hand, all that is left to do is call `http/start` on the
server map. This will invoke the internal start function for the
server map, and start listening for requests on the specified port.

Add a complete `-main` function to your own application:

```clj
;;...

(defn -main [& args]
  (-> service
      http/create-server
      http/start))
```

The final thing to do is to update your `project.clj` file to point at
`hello-pedestal.core/-main` when `lein run` is executed. Do that by
adding a `:main` key to `project.clj` like so:

```clj
:main hello-pedestal.core
```

## Running the Application

At this point, you should now be able to run your application and make
web requests to it. Start your application with the command `lein
run`:

```sh
$ lein run
...
```

Now, visit <localhost:8080/hello> in your browser or via cURL:

```sh
$ curl localhost:8080/hello
Hello, World!
```

It works!

## Slimming Down Logging

* TODO: do we include logging in getting started?
