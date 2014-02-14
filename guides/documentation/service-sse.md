---
title: Server-Sent Events (SSE)
---

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

The Pedestal service library includes support for Server-Sent Events,
or SSE. The SSE protocol defines a mechanism for sending event
notifications from a server to a client using a form of long polling.
However, unlike conventional long polling, SSE does not send each
event as a separate response, with an expectation that the client will
make a new request in between each one. Rather, SSE sends all its
events as part of a single response stream. The stream is kept alive
over time by sending events and/or periodic heart-beat data. In the
event that the stream is closed for some reason, the client can send a
request to re-open it and events notifications can continue. All
modern browsers have built in support for SSE via the _EventSource_
API.

You can setup an event source endpoint by defining a route that maps
requests to an interceptor returned from the
_io.pedestal.http.sse/start-event-stream_ function. The
resulting SSE interceptor processes a request by:

- pausing interceptor execution (see [Service Async](/documentation/service-async))

- sending the appropriate HTTP response headers to tell the client that
  an event stream is starting.

- initiating a timed heartbeat to keep the connection alive

and

- passing the current interceptor context to the _stream-ready-fn_ function that was
  passed as an argument to _start-event-stream_ (previously
called _sse-setup_, which is still supported for backward compatibility).

The _stream-ready-fn_ is responsible for using the context or storing it for
later use by some other piece of code.

Events can be sent to the client using the
_io.pedestal.http.sse/send-event_ function. It takes the context
passed to the _stream-ready-fn_, an event name and event data as
arguments.

_Note that in the current implementation the data must be a string.
This restriction will be removed in the future._

If a client closes its connection, the call to _send-event_ will
throw a _java.io.IOException_. The calling code should catch it and
clean up the streaming context.

When a streaming context is no longer needed, either because there are
no more events to send or the connection was broken by the client, it
must be cleaned up by calling the
_io.pedestal.http.sse/end-event-stream_ function.

Here is an example that shows how an SSE streaming context is created
and used.

```clj
(def a-stored-streaming-context (atom nil))

(defn clean-up []
  (when-let [streaming-context @a-stored-streaming-context]
    (reset! a-stored-streaming-context nil)
    (end-event-stream streaming-context)))

(defn notify [event-name event-data]
  (when-let [streaming-context @a-stored-streaming-context]
    (try
      (send-event streaming-context event-name event-data)
    (catch java.io.IOException ioe
      (clean-up)))))

(defn store-streaming-context [streaming-context]
  (reset! a-stored-stream-context streaming-context))

(defroutes route-table
  [[["/events" {:get [::events (start-event-stream store-streaming-context)]}]]])
```

The _store-streaming-context_ function is passed to _start-event-stream_. It is
called when the streaming context is ready. It stores the streaming
context in the _a-stored-streaming-context_ atom. (A more sophisticated
implementation would store it in a map keyed by some other information
in the context, e.g., a cookie.)

The _notify_ function is used to send an event to the client attached
to the stream. If an _IOException_ is thrown, it catches it and cleans
up the streaming context by calling _clean-up_. The _clean-up_
function can also be used directly when there are no more events to
send.

It is important to understand that the server-sent events
infrastructure uses the low-level streaming mechanism described
[here](/documentation/service-streaming). As such, it is subject to the major
limitation of that approach: once events have been streamed, any
interceptors that post-process the response from the SSE interceptor
will not be able change what was sent on the wire. Interceptor paths
that use the SSE interceptor should not include interceptors that
expect to alter the response, e.g., by setting cookies. If they do,
the interceptor that handles writing response data to the HTTP output
stream will log an exception indicating that the data could not be
sent. An interceptor can use the
_io.pedestal.http.impl.servlet-interceptor/response-sent?_ function to
determine whether a response has already been sent by an SSE (or
equivalent) interceptor.

