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

# Server-Sent Events

The Pedestal service library includes support for
[Server-Sent Events](http://www.w3.org/TR/eventsource/),
or SSE. The SSE protocol defines a mechanism for sending event
notifications from a server to a client using a form of long polling.
However, unlike conventional long polling, SSE does not send each
event as a separate response, with an expectation that the client will
make a new request in between each one. Rather, SSE sends all its
events as part of a single response stream. The stream is kept alive
over time by sending events and/or periodic heart-beat data. In the
event that the stream is closed for some reason, the client can send a
request to re-open it and events notifications can continue. All
modern browsers have built in support for SSE via the `EventSource`
API.

You can setup an event source endpoint by defining a route that maps
requests to an interceptor returned from the
`io.pedestal.http.sse/start-event-stream` function.
`start-event-stream` takes as an argument a `stream-ready-fn`,
described below. The resulting SSE interceptor processes a request by:

- pausing interceptor execution (see [Service Async](service-async.md))

- sending the appropriate HTTP response headers to tell the client that
  an event stream is starting.

- initiating a timed heartbeat to keep the connection alive

and

- calling the `stream-read-fn` with two arguments: a core.async
  channel and the current interceptor context.

The `stream-ready-fn` is responsible for using the channel and/or
context or storing it for later use by some other piece of code.
Events can be sent to the client by putting maps with keys `:name` and
`:data` to the channel. Closing the channel will result in the SSE
connection being cleaned up.

`Note that in the current implementation the data must be a string.
This restriction will be removed in the future.`

If a client closes its connection, the event channel will close,
causing subsequent puts to the channel to return false. The event code
should detect this and clean up any allocated resources.

Here is an example that shows how an SSE event stream can be used.

```clojure
(defn stream-ready [event-chan context]
  (dotimes [_ 20]
    (async/>!! event-chan {:name "foo" :data "bar"})
    (Thread/sleep 1000))
  (async/close! event-chan))

(defroutes route-table
  [[["/events" {:get [::events (start-event-stream stream-ready)]}]]])
```

It is important to understand that the server-sent events
infrastructure uses the low-level streaming mechanism described
[here](service-streaming.md). As such, it is subject to the major
limitation of that approach: once events have been streamed, any
interceptors that post-process the response from the SSE interceptor
will not be able change what was sent on the wire. Interceptor paths
that use the SSE interceptor should not include interceptors that
expect to alter the response, e.g., by setting cookies. If they do,
the interceptor that handles writing response data to the HTTP output
stream will log an exception indicating that the data could not be
sent. An interceptor can use the
`io.pedestal.http.impl.servlet-interceptor/response-sent?` function to
determine whether a response has already been sent by an SSE (or
equivalent) interceptor.
