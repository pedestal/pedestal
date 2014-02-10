# cors demo

Here we demonstrate an implementation of [cross-origin resource sharing](http://en.wikipedia.org/wiki/Cross-origin_resource_sharing).

In order to fully demonstrate this feature, we must start two running instances of this application on ports 8080 and 8081. A static
page with embedded javascript is served from http://localhost:8080/js, and it consumes an SSE EventSource found at http://localhost:8081/.

1. Start two instances on different ports: `lein run 8080` and `lein run 8081`
1. Open the Javascript console, as all output from the sample will be
   displayed there.
1. Visit [localhost:8080/js](http://localhost:8080/js) to load the
   event consumer, and watch the JavaScript console. The inline JavaScript returned
   will attempt to access a service on port 8081, a different origin. If allowed,
   the event source passes back an event containing the thread id, which is consumed
   and displayed in the console.

In `src/cors/service.clj`, you will find a definition of `cors-interceptor` that adds CORS headers
when the origin matches the authorized origin.

For more detailed information, please consult the `NOTES.md` document alongside this README.

## Thanks

This samples uses the `EventSource` polyfill from [Yaffle/EventSource](https://github.com/Yaffle/EventSource).

## Configuration

To configure logging see config/logback.xml. By default, the app logs to stdout and logs/.
To learn more about configuring Logback, read its [documentation](http://logback.qos.ch/documentation.html).

## Links

* [Other examples](https://github.com/pedestal/samples)


License
-------
Copyright 2013 Relevance, Inc.
Copyright 2014 Cognitect, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file epl-v10.html at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.
