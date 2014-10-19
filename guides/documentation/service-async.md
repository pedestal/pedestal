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

# Asynchronous Services

The interceptor infrastructure supports asynchronous processing. When
processing a request, you can pause and release the current thread.
Later, another thread can resume processing and deliver a response.
This allows long running work to occur without blocking a Web server
thread.

Here is some code that needs to wait for something to happen:

```clj
    (interceptors/defhandler takes-time [req]
      (response (wait-for-something-that-takes-a-long-time req)))

    (defroutes routes
      [[["/takes-time" {:get takes-time}]]])
```

Requests for `/takes-time` will block until the wait call completes,
stopping the Web server thread from handling other work.

This code can be made rewritten so that it releases the Web server
thread, as shown below:

```clj
    (interceptors/defbefore takes-time [{req :request :as context}]
      ;; give back the web server thread after doing work in body
      (io.pedestal.impl.interceptor/with-pause
        [paused-context context]
        ;; kick off another thread to wait
        (future
          ;; wait
          (let [result (wait-for-something-that-takes-a-long-time req)]
            ;; resume with context that includes response
            (io.pedestal.impl.interceptor/resume
              (assoc paused-context :response (response result)))))))

    (defroutes routes
      [[["/takes-time" {:get takes-time}]]])
```

The `io.pedestal.impl.interceptor/with-pause` macro pauses interceptor
processing and returns the calling thread. The
`io.pedestal.impl.interceptor/resume` function resumes processing
on another thread.

It is important to note that pausing and resuming an interceptor path
only affects how threads are used. While paused, the context for the
ongoing request still holds the pipe back to the client.

The simple `handler` function that took a Ring request map and
returned a Ring response map is replaced with a _before_ function,
that takes and returns a Pedestal interceptor context. This is
required because the `with-pause` macro and `resume` functions work
with contexts, not Ring requests and response.

In this example, the Web server's thread is freed, but another worker
thread is blocked. This is not a requirement. Here is an example that
releases the Web server thread without using another thread.

```clj
    (defn resume-fn [context result]
      (io.pedestal.impl.interceptor/resume
        (assoc context :response (response result))))

    (interceptors/defbefore takes-time [{:request req :as context}]
      ;; give back the web server thread after doing work in body
      (io.pedestal.impl.interceptor/with-pause
        [paused-context context]
        ;; give context to some other code that will resume it
        ;; by extracting resume-fn and calling it, passing result
        (give-context-to-code-that-will-store-it
          (assoc paused-context :resume-fn (partial resume-fn paused-context)))))

    (defroutes routes
      [[["/takes-time" {:get takes-time}]]])
```

In this case, the assumption is that some other piece of code will use
the stored context to complete the request at some point in the
future, perhaps in response to an event that has occurred. This
approach can be used to implement long-polling. In fact, this is how
Pedestal's built in support for server-sent events works.

(Note that both these examples use functions in the
`io.pedestal.impl.inteceptor` namespace. The code in this
namespace is subject to change.)

For more about streaming, see [Streaming Responses](service-streaming.md). For more
about SSE, see [Server-Sent Events](service-sse.md).
