# Pedestal

![CI](https://github.com/pedestal/pedestal/workflows/CI/badge.svg)
[![Clojars Project](https://img.shields.io/clojars/v/io.pedestal/pedestal.service.svg)](https://clojars.org/io.pedestal/pedestal.service)
[![Home Page](https://img.shields.io/badge/Docs-Documentation-blue)](http://pedestal.io)


Pedestal is a set of libraries written in Clojure that aims to bring
both the language and its principles (Simplicity, Power and Focus) to
server-side development.

**Pedestal requires Java 1.11+ and Servlet 3.1**

## Getting the Latest Stable Release

**Please see our [Releases](https://github.com/pedestal/pedestal/releases) for
version details, updates, and necessary migration steps.**

## Notable capabilities

  * Secure by default: automatic use of secure headers, CSRF (cross-site request forgery) protection, and other
    essential security practices right out of the box
  * Flexible: 
    * Pedestal builds on top of your choice of several servlet containers, including Jetty and Tomcat
    * Pedestal can work with non-servlet containers such as nginx or Netty
    * Everything from servlet container integration, to routing table notation is easily overridden or extended
    * Metrics can be published to JMX, StatsD, Cloudwatch, and more 
    * Behavior is defined by small [interceptors](http://pedestal.io/reference/interceptors) that can be easily combined and extended
  * Fast:
    * High-performance [prefix-tree router](http://pedestal.io/reference/prefix-tree-router) to dispatch incoming requests
    * Full support for asynchronous request processing on top of Clojure's [core.async](https://github.com/clojure/core.async)
  * Mature:
    * Integrated linking and testing tools
    * Integrated logging, [tracing](./samples/tracing-interceptor), and [runtime metrics](./samples/helloworld-metrics)
    * Advanced error handling, even for for async systems
    * Support for [WebSockets](./samples/jetty-web-sockets) and for [server-sent events]()
    * Support for Cross-origin resource sharing (CORS)
    * HTTP/2, HTTP/2 Cleartext, and ALPN support

## Principles

We prefer _data_ over _functions_, and functions over _macros_.

Open subsystems, such as routing, are defined in terms of a well-defined protocol so that applications can seamlessly
integrate their own solutions when necessary.

We feel that [interceptors](http://pedestal.io/reference/interceptors) are the ideal way to implement these ideals.

## Getting started

### Starting a new project

Use [leiningen](https://github.com/technomancy/leiningen) (2.2.0+) to create a new
Pedestal service. This will automatically pull templates from
<http://clojars.org>

```bash
lein new pedestal-service the-next-big-server-side-thing
```

See the [samples](./samples) or [Pedestal.io docs site](http://pedestal.io/) for information on Pedestal,
including:

 * Getting started tutorial and other guides
 * Main concepts
 * Advanced usage
 * API docs
 * Reference docs

## Digging deeper

### Roadmap

Our primary focus for the near future is Pedestal documentation, sample
applications and improving general ease of use.

Pedestal's roadmap gets captured [within the GitHub issues](https://github.com/pedestal/pedestal/issues).


### Documentation

 * [Pedestal documentation](http://pedestal.io/)
   * Older [docs](./guides/documentation) are coupled within this repository - some may be outdated.
 * The latest [API Docs](http://pedestal.io/api/index).
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

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details on contributing to Pedestal.

### Running the tests

From the `tests` subdirectory, execute 'clj -X:test`.

---

## License
Copyright 2013 Relevance, Inc.

Copyright 2014-2023 Cognitect, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file [epl-v10.html](epl-v10.html) at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.
