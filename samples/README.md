# Pedestal Samples

This is a collection of examples of services using the
[pedestal](http://pedestal.io) toolkit.

If you're new to Pedestal, we recommend that you have a look at
[our documentation](../guides) and then start
with the examples.

## Samples

* [Hello-world](./hello-world) A minimal service that exhibits the
  bare-minimum necessary to integrate Pedestal into an applicaiton.
* [Ring-middleware](./ring-middleware), shows how to use
  [Ring](https://github.com/ring-clojure/ring) middlewares with
  services
* [Server-with-links](./server-with-links), a service that
  demonstrates generating links to routes using `url-for`
* [Template-server](./template-server), a service that shows how to
  use different template engines
* [Cors](./cors)
* [Immutant/Wildfly](./immutant), a service that can be deployed standalone
  (on Immutant/Undertow) or [within](http://immutant.org/tutorials/wildfly/)
  [Wildfly](http://wildfly.org/)
* [Server-sent-events](./server-sent-events)

## Contributing

The Pedestal samples follow a similar procedure to Pedestal's
[CONTRIBUTING.md](https://github.com/pedestal/pedestal/blob/master/CONTRIBUTING.md).

## License
Copyright 2013 Relevance, Inc.
Copyright 2014-2019 Cognitect, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file [epl-v10.html](epl-v10.html) at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.

