# Using WebSockets in Jetty

This sample illustrates how to use WebSockets with Pedestal and Jetty.

## Getting started

1. Start a REPL with `lein repl`
2. Start the server within the REPL with `(def serv (run-dev))`
3. In your browser's JavaScript console

```javascript
w = new WebSocket("ws://localhost:8080/ws")
w.onmessage = function(e) { console.log(e.data); }
w.onopen = function(e) { w.send("Hello from the Client!"); }
w.onclose = function(e) { console.log("The server closed"); }
```

You'll notice the log message in Clojure REPL

4. Send a message to the client and close with `(service/send-and-close!)`

## Configuration

To configure logging see config/logback.xml. By default, the app logs to stdout and logs/.
To learn more about configuring Logback, read its [documentation](http://logback.qos.ch/documentation.html).

## Links
* [Other examples](https://github.com/pedestal/samples)

License
-------
Copyright 2014 Cognitect, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file epl-v10.html at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.

