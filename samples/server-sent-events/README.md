# server-sent-events

Demonstrates setting up, using and tearing down a server-sent-events
event stream. Events are sent from multiple threads.

## Getting Started

1. Start the application: `lein run`
2. Use `curl http://localhost:8080` to see event stream data.
3. Read the source code at src/server_sent_events/service.clj.

#### Working with events in ClojureScript

1. Build the client application: `lein cljsbuild once`
2. Start the application: `lein run`
3. Open a browser to: `http://localhost:8080/index.html` and open the JavaScript Console
4. Read the source code at src/server_sent_events/client/main.cljs

## Configuration

To configure logging see config/logback.xml. By default, the app logs to stdout and logs/.
To learn more about configuring Logback, read its [documentation](http://logback.qos.ch/documentation.html).

## Links
* [Other examples](https://github.com/pedestal/samples)

