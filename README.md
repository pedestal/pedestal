# Pedestal [![Build Status](https://travis-ci.org/pedestal/pedestal.png)](https://travis-ci.org/pedestal/pedestal)

Pedestal is a set of libraries written in Clojure that aims to bring
both the language and its principles (Simplicity, Power and Focus) to
server-side development.

## Getting the Latest Release

Leiningen dependencies:
```
[io.pedestal/pedestal.service       "0.3.1"]
[io.pedestal/pedestal.service-tools "0.3.1"] ;; Only needed for ns-watching
[io.pedestal/pedestal.jetty         "0.3.1"]
[io.pedestal/pedestal.immutant      "0.3.1"]
[io.pedestal/pedestal.tomcat        "0.3.1"]
```

## Getting started

### Starting a new project

Use [leiningen](https://github.com/technomancy/leiningen) (2.2.0+) to create a new
Pedestal service. This will automatically pull templates from
<http://clojars.org>

```bash
# To create a new service:
lein new pedestal-service the-next-big-server-side-thing
```

See [documentation](./guides/documentation) for information on Pedestal concepts and
advice on getting started.

## Digging deeper

### Roadmap

Our primary focus for the near future is Pedestal documentation, sample
applications and improving general ease of use.

### Documentation

 * [Pedestal documentation](./guides/documentation) is coupled within this repository.
 * The latest [API Docs](http://pedestal.github.io/pedestal).
 * Build your own API docs with `lein doc`

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

---

## License
Copyright 2013 Relevance, Inc.

Copyright 2014-2015 Cognitect, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file [epl-v10.html](epl-v10.html) at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.
