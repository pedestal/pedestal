# Pedestal

![CI](https://github.com/pedestal/pedestal/workflows/CI/badge.svg)
[![Clojars Project](https://img.shields.io/clojars/v/io.pedestal/pedestal.service.svg)](https://clojars.org/io.pedestal/pedestal.service)
[![Home Page](https://img.shields.io/badge/Docs-Documentation-blue)](http://pedestal.io)


Pedestal is a set of libraries written in Clojure that aims to bring
both the language and its principles (Simplicity, Power, and Focus) to
server-side development.

Pedestal features:
- Secure by default
- Batteries included, but designed for extensibility
- Asynchronous request handling
- Server Sent Events and WebSockets as first class citizens
- Integrated logging, metrics, and tracing
- Default integration with [Jetty 12](https://eclipse.dev/jetty/) and [Http-Kit](https://github.com/http-kit/http-kit)

You can stand up a basic Pedestal server in just a few lines of code
(see the [guides in the documentation](https://pedestal.io/pedestal/0.7/guides/hello-world.html)), but Pedestal is designed to grow with you, 
as your application matures and expands.

See the [full documentation](http://pedestal.io) for far more detail about
using Pedestal, its design, and its philosophy.

Pedestal requires Clojure 1.11 or later, and works with Servlet API 5.0 and Java 17 and up.

## Support

Primary support is on the [#pedestal channel of Clojurians Slack](https://clojurians.slack.com/archives/C0K65B20P).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details on contributing to Pedestal.

### Running the tests

From the `tests` subdirectory, execute `clj -X:test`.

---

## License
Copyright 2013 Relevance, Inc.

Copyright 2014-2025 Cognitect, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file [epl-v10.html](epl-v10.html) at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.
