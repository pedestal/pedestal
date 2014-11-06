# cors demo

Here we demonstrate an implementation 
of [cross-origin resource sharing](http://en.wikipedia.org/wiki/Cross-origin_resource_sharing).

In order to fully demonstrate this feature, we must 
show three things:

1. A server that provides SSE EventSource information <http://localhost:8081/>
2. A client with a white-listed origin that can reach that 
   information <http://localhost:8080/listener.html>
3. A client with a non-white-listed origin that cannot reach 
   that information <http://localhost:8082/listener.html>

To demonstrate this, begin three running instances of this application on ports 8080, 8081, and
8082 (using `lein run 808X`). Visit the listener.html addresses above.
Open your javascript console and click the link on the page to start the
event listener for each page.

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
