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

# Interceptors

## Ring Request Processing

Any discussion of Interceptors should start with two important facts:

1. Interceptors are more complex to write than ring middlewares.
2. Why would anyone ever choose to embrace this additional complexity?

Let's start by first examining Ring's approach to request processing.

Ring middlewares embrace possibly the simplest abstraction for
handling HTTP requests. The incoming request is modeled as a map of
data, it is fed to a function which returns a response. The response
is interpreted as a map of data, specific keys in the response are
extracted and used to build an HTTP response which is sent back to the
client. In this model, composition is achieved by using higher order
functions of other functions. Conventionally, a `wrap-functionality`
function is written which accepts a function of a request, and returns
a new function of a request which exhibits the new composite
functionality. Sessions are a good example; the
[wrap-session](https://github.com/ring-clojure/ring/blob/master/ring-core/src/ring/middleware/session.clj)
ring middleware accepts a handler function. It returns a new function
which, when invoked, extracts data from the request to re-establish a
map of session data, and associates this session data into the request
map. This new request map is then passed to the wrapped handler,
eventually producing a response. The response is examined for session
data, which gets processed and eventually encoded in additional
headers which will be sent back to the client. The modified response
is returned out of the wrapping function, providing the composite
behavior transparently to the wrapped function.

This strategy works well and it is possible to compose many different
concerns in web server processing isolated from each other, but it has
an important limitation. Because the mechanism of composition is
composite evaluation, the total composition of processing a request
must occur within the context of one thread. While it is possible to
suspend the thread until other processing is finished, there's no
convenient mechanism to dissociate the request's processing from the
thread which starts servicing it, and resume it later with another
different thread. Having a large number of passive requests which can
be serviced at a later time (e.g. long polling, server sent events,
requests waiting for status from a long running process) consumes a
commensurately large number of threads. Maintaining context over the
life of the request's processing makes use of closure scopes and the
call stack to retain values calculated before request processing to be
used after a response has been generated.

![Ring Middleware Composition](https://raw.githubusercontent.com/pedestal/pedestal/master/guides/documentation/middlewares.png)

To summarize the qualities of ring request processing:

1. Composition is achieved by middlewares knowing about each other and
    conditionally calling the other middlewares they know about.

2. The information for chaining is hidden away in closure scope. An
    outside observer working with a ring middleware chain cannot see where
    it goes.

3. Responsibility of chaining behavior is diffused through all
    middlewares. Each middleware is responsible for calling the next in
    the chain.

4. Execution of the whole chain is bound to one thread.


## Interceptor Execution Stages

Interceptors aim to explicitly solve the issue of request processing
being coupled tightly to one thread. It does this with two mechanisms:

1. Interceptors operate on a
    [context](service-context-reference.md) which
    explicitly retains all data associated with processing one
    request.

2. Interceptors allow the processing of one context to be paused in
    one thread, and resumed in another thread.

In order for interceptors to achieve this, they do not operate by
invoking each other or by wrapping as higher ordered functions, but
instead as members of an execution queue, where each interceptor is
invoked and its return value retained to be invoked by the next
interceptor. An ordered collection of interceptors to execute is
referred to as a `path`. A path of interceptors will be executed by
the interceptor engine by progressing through `stages`. There are five
such stages of interceptor execution:

1. Enter
2. Leave
3. Error
4. Pause
5. Resume

The most conventional stages, and the ones end users are most likely
to use, are enter and leave. As an interceptor path is processed, the
enter stage of each interceptor is called with context in turn. This
continues until calling the enter function of the last interceptor in
the path. At this point, the leave stages of the interceptors are
called in reverse order, that is, the first interceptor specified in
its path will have its leave function called last.

Alternatively, an interceptor may call `terminate`, which will
terminate execution of the path immediately and begin invoking leave
stages. If the context contains a terminator predicate, as associated
into a context with the `terminate-when` function, which returns true
after the processing of any interceptor, the execution will terminate
and the leave stages of interceptors will begin to be invoked.

The error stage is used for exception handling. If during any stage an
uncaught exception is thrown, then the interceptor framework will
catch the exception, and call the immediately preceding interceptor
with the context and the caught exception. If this interceptor
rethrows the exception, it will be caught again and provided to the
next most immediately preceding interceptor. If the interceptor
returns a context, processing will continue by calling the leave
functions of preceding interceptors, as if the last interceptor in
the path had been reached.

During execution, an interceptor may revert to the pause state (most
often using the `with-pause` macro). In this case, each interceptor in
the path which has previously had its enter function called, has their
pause function called in reverse order. When all of the pause
functions have been called, the body of `with-pause` executes with the
context resulting from all of the pause invocations. Finally,
interceptor processing terminates in that thread, but the context upon
which the interceptors had been processing may be retained in memory.

Any thread, including the originating thread, or a different thread
which receives the context, may then resume interceptor execution. On
resuming, the resume functions of each interceptor are called (in the
same order as the enter functions were called), until returning to the
point in the path after the interceptor which paused. The enter
functions of further interceptors in the path are invoked as if no
pause had occurred. A single context may pause and resume an arbitrary
number of times.

## Request Processing Across Threads

This architecture allows for processing a single request across
multiple threads. The thread which initially begins processing the
request invokes the `with-pause` macro, which implicitly invokes the
pause stage of all previous interceptors in the path, captures the
resultant context, and binds it to the name provided in the binding
form before executing the body. In the body, the context is made
available to other threads through a concurrency construct (such as a
concurrent identity like an atom, ref, or agent, or a concurrent
processing form like a future or a delay). The body terminates, and
the first thread terminates it's processing entirely. A new thread
calls resume on the post pause context, which resumes interceptor
execution with the context from the paused thread. The
[sse interceptor](https://github.com/pedestal/pedestal/blob/master/service/src/io/pedestal/http/sse.clj),
which creates a channel for servers to communicate with clients,
demonstrates this pattern.

## Adding and Removing Interceptors

As an interceptor path is traversed, the context is continually
re-evaluated to determine what stage of which interceptor should fire
next. The return value of one interceptor may itself be a context with
a path where more interceptors have been added, where the total
interceptor path can be examined or chained, or where additional
terminators can be introduced. The
[routing interceptor](https://github.com/pedestal/pedestal/blob/master/service/src/io/pedestal/http/route.clj)
uses this feature to add additional interceptors to the interceptor
path after examining the incoming request to find a matching path to
dispatch requests to.

## Compatibility with Ring

The Pedestal service infrastructure is designed to be Ring-compatible
to the greatest extent possible. Specifically HTTP requests and
responses are represented as Ring-style maps, but held in a wrapping
Pedestal service context map.

All of the middlewares in Ring have been refactored so that in
addition to the conventional `wrap-xyz` function for building a
Ring-style middleware chain, there are `xyz-request` and
`xyz-response` functions. These functions process requests and
responses separately. The `wrap-xyz` functions have been refactored to
use the separate request and response processing functions.

The `io.pedestal.http.ring-middlewares` namespace defines
interceptors that use the new Ring `xyz-request` and `xyz-response`
functions, making all the standard Ring middlewares usable in Pedestal
services.

## Compared with Ring Middleware

![Interceptor Composition](https://raw.githubusercontent.com/pedestal/pedestal/master/guides/documentation/interceptors.png)

Consider the nature of Pedestal Service's Interceptors as compared
with Ring's Middlewares.

1. Composition is achieved by placing a number of interceptors into a
    queue for execution. This queue is traversed first in first out order.

2. Ordering and presence are clearly visible, it is data that can be
    worked with using all of Clojure's tools for working with seqs and
    `PersistentQueues`.

3. Responsibility of chaining behavior is delegated to the interceptor
    framework.

4. There exist tools for manipulating the chaining behavior at run time
    (e.g. terminating execution, enqueuing additional
    interceptors). Implementing consistent chaining behavior does not
    require diffusing that behavior through each interceptor.

5. Interceptors can know the entire queue of execution as it stands at
    their time of execution. It is possible to know what the last
    planned interceptor is before getting there. It's possible to know
    what the last interceptor which executed is. Most ring middlewares
    are only aware of what the immediately next middleware is.

6. Execution of the whole chain can be paused in one thread, then
    resumed in another different thread. A paused interceptor queue is
    data and does not consume a thread.

7. Interceptors do not need any information about any other
    interceptors to execute correctly. This information is available
    in the context, but it is not required.

## Interceptor Debugging

The interceptor framework logs the entry of each interceptor's
function, in each stage, at the debug log level. The interceptor
framework logs the entry and the context it is currently processing at
the entry of each interceptor's function in each stage at the trace
log level. This is a useful way to determine what exactly is happening
between the interceptor framework and the interceptors it is firing,
but it is extremely verbose.

## Definition

An interceptor is one instance of an Interceptor record or a map with
:enter, :leave, :pause, :resume, and :error keys. An interceptorfn is
a function which returns an interceptor.

Pedestal includes macros for defining interceptors, and for defining
interceptorfns. These macros are conveniences for attaching a
symbolic name and docstring to an interceptor.

There are functions and macros for constructing interceptors that deal
with Ring requests and responses:

- The `on-request` function and `defon-request` macro define
   an interceptor with an enter function that takes a Ring request and
   returns a modified Ring request.

- The `on-response` function and `defon-response` macro define an
   interceptor with a leave function that takes a Ring response and
   returns a modified Ring response.

- The `middleware` function and `defmiddleware` macro define an
   interceptor with both an enter and a leave function.

There are equivalent functions and macros for building interceptors
that deal directly with context maps, named `before` and `defbefore`,
`after` and `defafter`, and `around` and `defaround`.

Existing Ring handler functions used at the end of middleware chains
that take a request and return a response can be referred to directly
from a service's route table. The routing infrastructure will wrap
them in an interceptor using the `handler` function.  Alternatively,
you can wrap them yourself using the `defhandler` macro.

These macros also flag the vars they create with metadata identifying
them as either interceptors or interceptorfns. Other pieces in the
Pedestal framework make use of this metadata to make intelligent
decisions about how to work with these vars.

## Porting Ring Code

You can port Ring code to Pedestal by:

- Reusing handler functions directly in a route-table (or by wrapping
  them in a call to `handler` or `defhandler`)

- Refactoring middleware functions into two separate functions, one
  that modifies a request and one that modifies a response and using
  them to define an interceptor using the `on-request`, `middleware`
  or `on-response` functions or the `defon-request`, `defmiddleware`
  or `defon-response` macros.

  You can build an interceptor that works directly with a context map,
  providing access to both Ring maps.

- If you are using Compojure for routing requests, rewrite your route
  definitions using the terse routing format (see
  [Service Routing](service-routing.md)). Any Ring middlewares that run
  before your Compojure routes should be replaced by interceptors that
  run before routing. Any middlewares specified in your Compojure
  routes should be replaced by interceptors referenced directly in
  your route definitions. There are interceptors provided for all the
  existing Ring middlewares. They are defined in the
  `io.pedestal.http.ring-middlewares` namespace.
