# Pedestal [![Build Status](https://travis-ci.org/pedestal/pedestal.png)](https://travis-ci.org/pedestal/pedestal)

## What is Pedestal

Pedestal is a set of Clojure libraries for building web applications.

It aims to bring Clojure's principles of Simplicity,
Power and Focus to server-side development.

Pedestal seeks to be a general-purpose web development platform, with
a focus on empowering developers to build rich, collaborative internet
applications requiring asyncrony, low-latency, or streaming (soft
real-time).


## Getting Started

In the [Getting Started](guides/00-getting-started.md) guide, you
will find a walk-through that introduces you to all of Pedestal's
moving parts via the creation of a new server-side application.

Visit any one of the following guides for more information on Pedestal concepts:

<!-- TODO - Flesh out this list. -->

- [Getting Started](guides/00-getting-started.md)
- Handlers
- Intereceptors (Middleware)
- Routing
  - Linking
- Templating
- Deployment
  - CORS
- Async
  - Delayed Responses (core.async)
  - SSE
  - WebSockets (Later)
- [Context Map Reference](guides/context-map.md)


## Roadmap

Our primary focus for the near future is Pedestal documentation, sample
applications and improving general ease of use.

## Supported Platforms

At present Pedestal supports OSX and Linux environments. At this time we do not
support using Pedestal on a Windows environment.

**Some good news**: We will still gladly accept pull requests extending our
Windows support

**The bad news**: We will not invest significant amounts of time into
diagnosing or correcting Windows issues.

## Join the Conversation

* Follow [@pedestal_team on Twitter](http://twitter.com/pedestal_team)
* Subscribe to [pedestal-users](https://groups.google.com/d/forum/pedestal-users)
  and [pedestal-dev](https://groups.google.com/d/forum/pedestal-dev)

## Looking for Pedestal App?

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

Copyright 2014 Cognitect, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file [epl-v10.html](epl-v10.html) at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.
