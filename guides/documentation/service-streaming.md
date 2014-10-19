<!--
 Copyright 2013 Relevance, Inc.
 Copyright 2014 Cognitect, Inc.

 The use and distribution terms for this software are covered by the
 Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
 which can be found in the file epl-v10.html at the root of this distribution.

 By using this software in any fashion, you are agreeing to be bound by
 the terms of this license.

 You must not remove this notice, or any other, from this software.
-->

# Streaming Responses

In some cases, you may want to stream large responses back to clients.
Streaming makes more efficient use of space when you are dealing with
large response bodies. It also allows a client to start consuming data
as quickly as possible.

Here are some examples of potentially large values that you might want
to stream back to clients:

- the contents of a lazy sequence
- a file or resource stored on disk
- a byte stream retrieved from some service, e.g., an image on S3

In these cases, an interceptor can return a context containing a Ring
response map whose body is an instance of a type that supports
streaming:

- `clojure.lang.IPersistentCollection` - the collection is printed
  directly into the HTTP output stream. Lazy sequences implement
  `IPersistentCollection`.

- `clojure.lang.Fn` - the function is invoked when the response body
  is written. The function is passed the HTTP output stream and can
  write directly to it. You can use the
  `io.pedestal.http.impl.servlet-interceptor/write-body-to-stream`
  function to do this, but you don't have to.

- `java.io.File` or `java.io.InputStream` - the contents are copied in
  chunks to the HTTP output stream in chunks using `clojure.java.io/copy`
  when the response body is written.

- `io.pedestal.http.impl.servlet-interceptor/WriteableBody` - the
  instance writes itself to the HTTP output stream.

The thread completing the interceptor path will write the body out to
the HTTP response stream. If the request is processed synchronously,
the work will be done on the Web server thread. If the request is
processed asynchronously, the work will be done on whatever thread
resumed processing (see [Service Async](service-async.md)).

Here is an example of an interceptor that returns an arbitrarily large
volume of data.

```clj
    (defn range [req]
      (let [limit (get-in req [:query-params :limit] 10)]
        (response (range limit))))

    (defroutes route-table
      [[["/range" {:get range}]]])
```

Because the return value is an `IPersistentCollection`, it is streamed out to the
client.

## Low-level streaming

In order to use the streamed response body types described in the
previous section, you must complete processing of a request. In doing
so, you are dedicating a thread (either a Web server thread or your
own worker thread) to copying data into the HTTP output stream. After
that streaming process completes, no more data can be sent.

There may be cases, however, where you want to stream data back but
then continue processing. You can do that from within an interceptor
by invoking the
`io.pedestal.http.impl.servlet-interceptor/write-response` and
`io.pedestal.http.impl.servlet-interceptor/write-response-body`
functions.

The `write-response` function takes a context map a Ring response
map. It sets the HTTP response status and headers. It also writes the
body, if any, to the HTTP output stream.

The `write-response-body` function takes a context map and an object
to write to it.

It is important to understand that writing response data this way
essentially removes you from the normal interceptor processing path,
in the sense that, while the interceptors in your path may
post-process your response, no changes they make to it will be sent on
the wire. So, for instance, you cannot use this technique with an
interceptor that post-processes your response and sets a cookie value.
The updated cookie will never be sent.

If an interceptor returns a response after a response has been
streamed out, the interceptor that handles writing response data to
the HTTP output stream will log an exception indicating that the data
could not be sent. An interceptor can use the
`io.pedestal.http.impl.servlet-interceptor/response-sent?` function to
determine whether a response has already been sent by an SSE (or
equivalent) interceptor.

This functionality is used to implement Server-Sent Events,
documented [here](service-sse.md).
