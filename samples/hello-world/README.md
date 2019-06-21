# hello-world

A bare-minimum Pedestal service that greets visitors.

## Usage

Launch the application using the command `lein run`.

Once the application has started, visit the following URLs via
a browser or cURL:

* <localhost:8080/hello>
* <localhost:8080/hello?name=You>

For example:

```sh
$ curl -i "localhost:8080/hello"
HTTP/1.1 200 OK
Date: Fri, 25 Apr 2014 14:43:34 GMT
Content-Type: text/plain
Transfer-Encoding: chunked
Server: Jetty(9.1.3.v20140225)

Hello, World!

$ curl "localhost:8080/hello?name=You"
Hello, You!
```

## Links
* [Other Pedestal examples](http://pedestal.io/samples)

## License

Copyright 2013 Relevance, Inc.
Copyright 2014-2019 Cognitect, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file epl-v10.html at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.
