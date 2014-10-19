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

# Service Contexts

## Motivation

This document describes the keys and values of the context map which
Pedestal's interceptor framework expects. It is crucial to understand
that this reference is **neither exhaustive nor
constraining**. Pedestal's interceptors will function by expecting the
described values at the described keys in a context, but the
interceptor framework is tolerant of and encourages additional keys
being added to the context map to allow for extension.

## Caveats

Some of the keys described in this document are in namespaces
containing implementation details,
e.g. io.pedestal.impl.interceptor. These namespaces always
contain an 'impl' token in the namespace hierarchy. The keys in these
namespaces do not represent a public interface and are subject to
change. Their inclusion in this document is not a commitment to keep
these keys or the nature of their values stable, and should not be
interpreted as such.

## Reference

The context map supports the following values:


* *:request* - The
   [ring request](https://github.com/ring-clojure/ring/blob/master/ring-core/src/ring/util/request.clj)
   map modeling the incoming request this interceptor path is
   servicing.

* *:response* - The
   [ring response](https://github.com/ring-clojure/ring/blob/master/ring-core/src/ring/util/response.clj)
   map modeling the outgoing response this interceptor path will
   return to the client. In some cases where data is streamed to
   clients during the execution of interceptors, data associated into
   the response map, such as response headers, may not be sent to the
   client.

* *:bindings* - A map of Var/value pairs, as accepted by
   `clojure.core/with-bindings`. Prior to delegating execution to any
   interceptor, the interceptor framework will ensure that the
   bindings specified in this map are installed using
   `clojure.core/with-bindings`. If this map is altered and included in
   the returned context from an interceptor, the new bindings in the
   map will be installed as thread local bindings prior to another
   interceptor's execution.

* *:servlet-request* - A `javax.servlet.http.HttpServletRequest`
   instance, provided to the Servlet instance which bootstrapped this
   interceptor path's execution.

* *:servlet-response* - A `javax.servlet.http.HttpServletResponse`
   instance, provided to the Servlet instance which bootstrapped this
   interceptor path's execution.

* *:servlet-config* - A `javax.servlet.ServletConfig` instance,
   retrieved from the Servlet instance which bootstrapped this
   interceptor path's execution.

* *:servlet* - The `javax.servlet.Servlet` instance which bootstrapped
   this interceptor path's execution.

* *:io.pedestal.impl.interceptor/execution-id* -
  Auto-incrementing long which can associate a single interceptor
  execution context across multiple threads.

* *:io.pedestal.impl.interceptor/queue* - A `PersistentQueue` of
   interceptors which still need to be executed during the 'enter'
   stage. So long as this value is not empty, the head of the queue
   will be removed and executed, then added to the end of
   *:io.pedestal.impl.interceptor/stack*

* *:io.pedestal.impl.interceptor/stack* - A `PersistentList` of
   interceptors which still need to be executed during the 'leave'
   stage. So long as this value is not empty, the tail of the list
   will be removed and executed.

* *:io.pedestal.impl.interceptor/pause-stack* - A
   `PersistentList` of interceptors which will have their pause fns
   called during the pause stage. This stack will be consumed as
   pause fns are called, leaving
   *:io.pedestal.impl.interceptor/stack* unchanged.

* *:io.pedestal.impl.interceptor/resume-stack* - A
   `PersistentList` of interceptors which will have their resume fns
   called during the resume stage. Initially starts as the reversed
   value of *:io.pedestal.impl.interceptor/stack*, and is
   consumed as an interceptor's execution is resumed.

* *:io.pedestal.impl.interceptor/terminators* - A seq of
   predicate fns. Each predicate fn is handed the present context in
   turn after each interceptor executes during the 'enter' stage. If
   any predicate fn returns true, the interceptor immediately enters
   the 'leave' stage.

* *:io.pedestal.impl.interceptor/error* - The most recently
   uncaught throwable thrown during the execution of an
   interceptor. If an uncaught exception is thrown during the enter
   stage, the enter stage will immediately cease. The error stage will
   immediately begin executing on each interceptor on the stack. Each
   interceptor's error fn, if present, will be called with the error
   and the context. If an interceptor error fn rethrows the same
   error, it will remain in the context and the next interceptor on
   the stack will handle the error. If an interceptor error fn throws
   a different exception, it will replace the error, and the next
   interceptor on the stack will handle the error. If an interceptor
   error fn returns the context as it receives it, the next
   interceptor on the stack will handle the error. If an interceptor
   error fn returns the context with the error dissociated, the next
   interceptor on the stack will have its leave fn called.

* *:io.pedestal.impl.interceptor/rebind* - A boolean,
   indicating that the thread bindings as specified in :bindings
   should be re-established using clojure.core/with-bindings

* *:io.pedestal.http.impl.servlet-interceptor/async* - A
   boolean, which will be set to true if this context has been paused
   and the servlet request being serviced with this context has an
   asynchronous context created for it.
