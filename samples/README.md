# Pedestal Samples

This is a collection of examples of services using the
[pedestal](http://pedestal.io) toolkit. They range from [very
simple](./helloworld_app) to [quite complex](./chat).

If you're new to pedestal, we recommend that you have a look at [our
documentation](http://pedestal.io/documentation) and then start with the
examples.

## Samples

* [auto-reload-server](./auto-reload-server), a **pedestal-service** that
    uses [ns-tracker](https://github.com/weavejester/ns-tracker) to reload
    changed code
* [cors](./cors)
* [jboss](./jboss), a **pedestal-service** that can be deployed in
    [JBoss](http://jboss.org)
* [ring-middleware](./ring-middleware), shows how to use
    [ring](https://github.com/ring-clojure/ring) middlewares with
    **pedestal-services**
* [server-sent-events](./server-sent-events)
* [server-with-links](./server-with-links), a **pedestal-service** that
    demonstrates links generated from routes
* [template-server](./template-server), a **pedestal-service** that shows
    how to use different template engines

## Contributing

The Pedestal samples follow a similar procedure to Pedestal's [CONTRIBUTING.md](https://github.com/pedestal/pedestal/blob/master/CONTRIBUTING.md).

## License
Copyright 2013 Relevance, Inc.
Copyright 2014 Cognitect, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file [epl-v10.html](epl-v10.html) at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.

