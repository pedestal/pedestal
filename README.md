# Pedestal [![Build Status](https://travis-ci.org/pedestal/pedestal.png)](https://travis-ci.org/pedestal/pedestal)

The Pedestal web application framework

## Installing Libraries

Run `lein sub install` in the top level directory to install all the
library components in your local Maven repository.

The demo project uses Leiningen checkouts to allow simultaneous
development of libraries and the application. See the [Leiningen FAQ](https://github.com/technomancy/leiningen/blob/master/doc/FAQ.md)
for details.

## Library Docs

To generate literate-programming-style documentation for the libraries, add the
[lein plugin for marginalia](https://github.com/fogus/lein-marginalia) to
your lein user profile. You can then `cd` into a library directory and run
`lein marg`. Docs will be placed in the ./docs directory of the respective library.

## Supported Platforms

At present Pedestal supports OSX and Linux environments.

At this time we do not support using Pedestal on a Windows environment.

**Some good news**: We will still gladly accept pull requests extending our
Windows support

**The bad news**: We will not invest significant amounts of time into
diagnosing or correcting Windows issues.

## Find out more

* Visit [our website](http://pedestal.io/)
* Follow [@pedestal_team on Twitter](http://twitter.com/pedestal_team)
* Subscribe to [pedestal-users](https://groups.google.com/d/forum/pedestal-users) and [pedestal-dev](https://groups.google.com/d/forum/pedestal-dev)

---

## License
Copyright 2013 Relevance, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file epl-v10.html at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.
