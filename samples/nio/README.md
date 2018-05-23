# nio

This sample demonstrates using a ByteBuffer as a response body. For large response
amidst a high number of concurrent connections, using NIO (via a ByteBuffer or
ReadableByteChannel) can increase your app's performance.

## For more information, consult the Pedestal 0.3.1 [release notes](https://github.com/pedestal/pedestal/releases/tag/0.3.1).

## Getting Started

1. Start the application: `lein run-dev` \*
2. Go to [localhost:8080](http://localhost:8080/) to see: `Hello World!`
3. Read your app's source code at src/nio/service.clj. Explore the docs of functions
   that define routes and responses.
4. Run your app's tests with `lein test`. Read the tests at test/nio/service_test.clj.
5. Learn more! See the [Links section below](#links).

\* `lein run-dev` automatically detects code changes. Alternatively, you can run in production mode
with `lein run`.

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


