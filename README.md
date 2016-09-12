# Pedestal [![Build Status](https://travis-ci.org/pedestal/pedestal.png)](https://travis-ci.org/pedestal/pedestal)

Pedestal is a set of libraries written in Clojure that aims to bring
both the language and its principles (Simplicity, Power and Focus) to
server-side development.

**Pedestal requires Java 1.8+ and Servlet 3.1**

## Getting the Latest Release

Leiningen dependencies:
```
[io.pedestal/pedestal.service       "0.5.1"]
[io.pedestal/pedestal.service-tools "0.5.1"] ;; Only needed for ns-watching; WAR tooling
[io.pedestal/pedestal.jetty         "0.5.1"]
[io.pedestal/pedestal.immutant      "0.5.1"]
[io.pedestal/pedestal.tomcat        "0.5.1"]
```

**Please see our [Releases](https://github.com/pedestal/pedestal/releases) for
version details, updates, and necessary migration steps.**

### Notable capabilities

 * Fast and secure by default (automatically uses secure headers, CSRF-protection, and other best practices)
 * A guiding principle of "data > functions > macros" - the core pieces of Pedestal
   are data-driven and programmed against protocols.  The entire platform is extensible.
 * A high-performance [prefix-tree router](https://github.com/pedestal/pedestal/pull/330)
   that is significantly faster and more space efficient than other Clojure web routers
   * When avoiding wildcard and param-routes, the router will be O(log32 N), otherwise O(log N), for the number of routes
 * The ability to plug-in any router, including one you write
 * The ability to express routes in any format you like
 * [Full/true async support](https://groups.google.com/d/msg/clojure/rKqT13Ofy4k/H9xvkZA9Yy4J) (Async Servlet + core.async + NIO),
   resulting in better performance and capacity than a synchronous-only solution
 * Advanced [error handling](https://github.com/pedestal/pedestal/pull/302) for async systems
 * Integrated streaming capabilities like [Server-sent events](https://github.com/pedestal/pedestal/tree/master/samples/server-sent-events)
 * Integrated support for Cross-origin resource sharing/[CORS](https://github.com/pedestal/pedestal/tree/master/samples/cors)
 * Integrated support for content negotiation
 * Integrated linking and testing tools
 * Integrated logging and runtime [metrics](https://github.com/pedestal/pedestal/tree/master/samples/helloworld-metrics) (which can be published to JMX, StatsD, Cloudwatch, and more)
 * A fundamentally simple system (absolutely everything is an interceptor; interceptors compose)
 * The ability to utilize Java Web technology directly in your service (Pedestal can integrate ServletFilters)
 * The ability to utilize Ring Middleware as Pedestal Interceptors
 * Support to run on Jetty, Immutant/Undertow, and Tomcat
 * The ability to run Pedestal services on non-Servlet containers (Vert.x, nginx, Netty, etc.)
 * HTTP/2, HTTP/2 Cleartext, and ALPN support
 * [WebSocket](https://github.com/pedestal/pedestal/tree/master/samples/jetty-web-sockets) support
 * [Docker](https://www.docker.com/) and [OSv](http://osv.io/) support
 * and more!


## Getting started

### Starting a new project

Use [leiningen](https://github.com/technomancy/leiningen) (2.2.0+) to create a new
Pedestal service. This will automatically pull templates from
<http://clojars.org>

```bash
# To create a new service:
lein new pedestal-service the-next-big-server-side-thing
```

See the [samples](./samples) or [documentation](./guides/documentation) for information on Pedestal concepts and
advice on getting started.

## Digging deeper

### Roadmap

Our primary focus for the near future is Pedestal documentation, sample
applications and improving general ease of use.

### Documentation

 * [Pedestal documentation](./guides/documentation) is coupled within this repository.
 * The latest [API Docs](http://pedestal.github.io/pedestal).
 * Build your own API docs with `lein sub install; lein docs`

### Supported Platforms

At present Pedestal supports OSX and Linux environments. At this time we do not
support using Pedestal on a Windows environment.

**Some good news**: We will still gladly accept pull requests extending our
Windows support

**The bad news**: We will not invest significant amounts of time into
diagnosing or correcting Windows issues.

### Find out more

* Follow [@pedestal_team on Twitter](http://twitter.com/pedestal_team)
* Subscribe to [pedestal-users](https://groups.google.com/d/forum/pedestal-users)
  and [pedestal-dev](https://groups.google.com/d/forum/pedestal-dev)

### Looking for Pedestal App?

 * Pedestal App now lives in [its own github repo](https://github.com/pedestal/pedestal-app).
 * Please see the [community announcement](https://groups.google.com/forum/#!topic/pedestal-users/jODwmJUIUcg) for more details.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details on contributing to Pedestal.

### Installing Libraries

To install Pedestal library components in your local Maven repository run
`lein sub install` from a local checkout of this repository.

### Running the tests

After installing all the library components, you can run the tests with
`lein sub test` from a local checkout of this repository.  The majority of the
tests are in the [service directory](./service/test/io/pedestal)

---

## License
Copyright 2013 Relevance, Inc.

Copyright 2014-2016 Cognitect, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file [epl-v10.html](epl-v10.html) at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.
