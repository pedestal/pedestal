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

# Asynchronous processing

The interceptor infrastructure supports asynchronous processing via
[core.async](https://github.com/clojure/core.async) channels. Any
interceptor that returns a channel will initiate asynchronous
behavior. When a new context is later written to the channel, the
interceptor path will continue processing with this new context in
place of the original context.

This allows long-running work to occur without blocking a Web server
thread.

Here is a synchronous handler that needs to wait for something to happen:

```clj
    (interceptors/defhandler takes-time [request]
      (ring-resp/response (lengthy-computation request)))

    (defroutes routes
      [[["/takes-time" {:get takes-time}]]])
```

Because this handler is synchronous, requests for `/takes-time` will
block until the call to `lengthy-computation` completes, stopping the
Web server thread from handling other work.

This code can be rewritten so that it releases the Web server
thread, as shown below:

```clj
    (interceptors/defbefore takes-time
      [context]
      (go
        (let [result (<! (lengthy-computation (:request context)))]
          (assoc context :response (ring-resp/response result)))))

    (defroutes routes
      [[["/takes-time" {:get takes-time}]]])
```

The `go` block allows work to take place asynchronously while the
thread is released.

The `lengthy-computation` function returns a channel that
conveys the result, `<!` will park the go block until that value is conveyed,
after which the `go` block continues to construct a response and `assoc` it
into the context.

The `go` block returns a channel that conveys that modified context, at
which point Pedestal picks back up to resume handling of the original request.
Since a response is now ready, the original request will
complete, and that response will be delivered to the client.

This facility allows a single value to be placed on a channel per
interceptor; for more extensive use of channels for SSE, see
[Server-Sent Events](service-sse.md).

For more about streaming, see
[Streaming Responses](service-streaming.md).
