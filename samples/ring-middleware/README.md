# Ring Middleware Sample

Pedestal incorporates existing ring middleware into its
interceptors functionality. In this example, we implement a service
that stores your name in the session via setting a session token
in the cookie store. We are depending on `ring.middleware.session.cookie`
for this functionality.

To make this work, we use `definterceptor` to define the interceptor
we'll need in the processing list to store a value in a cookie. From
`services.clj`:

```clojure
(definterceptor session-interceptor
  (middlewares/session {:store (cookie/cookie-store)}))
```

For further enlightenment, take a look at:

*  The pedestal ring-middlewares package: `io.pedestal.http.ring-middlewares`
*  The pedestal interceptors functionality: `io.pedestal.interceptor`

## See it in Action

1. Start the application: `lein run`
2. Go to [localhost:8080](http://localhost:8080/) and enter your
   name in the field. You will be directed to the `/hello` page, and
   your name displayed. So long as the service is not killed, you
   will be remembered.
3. Learn more! See the [Links section below](#links).

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
