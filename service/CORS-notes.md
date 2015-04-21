First general approach

We can in the 'enter' stage of the CORS interceptor examine the queue of interceptors to run. If any of the queue interceptors are SSE interceptors, then we should set headers eagerly.

If not, we can wait and set the CORS headers during the 'leave' stage of execution when the fully realized response is available.

Concerns:
1. Adds a coupling between CORS and SSE interceptors.
2. Order of definition of the interceptors

Second general approach:

Make SSE interceptor aware of headers that other interceptors would like to have inserted into the response.

We need to explicitly recognize that the transmission of headers and transmission of the response body are two different steps. Ring complects the two, but they are not essentially tied together, and SSE breaks them apart without any formalized protocols or lifecycle about this concern.

Concerns:
1. How do we simplify headers and body transmission without breaking ring?
2. Adding a 'headers' execution cycle to solve this problem means we go from one execution cycle (enter leave pause resume) to two (interceptor thread management, and interceptor header management). How many more times can we invoke this solution before it becomes untenable? Will increasing the cardinality hamstring us in the future?
3. What happens when two different interceptors want to put different values in the same header key?
4. Some interceptors work with headers in their after execution stage, is there a way to have the work they do on headers included transparently?
5. How general is this problem?  (relates to concern number two)

Third general approach:

Create a stub response in the SSE interceptor. Pass it through each interceptor on the stack's 'leave' stage, examining the differences on the response's headers. Collect the diffs, merge them, and this yields a set of headers to send immediately in the SSE interceptor.

1. Assumes every 'leave' stage is a pure function. (fire-the-missiles! anyone?)
2. Heavyweight if an interceptor for cpu intensive or blocking 'leave' stages that will be called twice now.
3. Significant risk of errors.

Discrete headers function could be called during leave as a default action potentially. This doesn't fold 'old' interceptors into functioning with the header execution cycle, but does mean new interceptors with header execution cycles are broadly applicable to both paths that need to realize just headers, and paths that can realize headers and bodies together.

Call realize-headers, and get a collection of headers immediately. No call, headers will be aggregated gradually through the natural interceptor lifecycle.

Might need to make currently executing interceptor available in context. Then an interceptor that wants to manipulate both the body and the headers can call its header function in the context of its leave function.

(cookies-response response) -> new-response

(defn ring-headerware-adapter
  [f]
  (fn [context]
      (let [response (f (:response context))]
        (:headers response))))


Tim's comments:

A hybrid of the first and second approach is to have CORS add the
headers it will put in the response to context. The SSE interceptor
would look for them and send them if present. The CORS interceptor
would add them to the response on leave if no response has been sent
(which can now be detected generically). This would add a dependency
from SSE to CORS, not the other way round - CORS wouldn't know
anything about SSE, it would provide its headers for any upstream
interceptor that might take over the output stream and write a
response directly. I would prefer the SSE -> CORS dependency over the
CORS -> SSE dependency.

About the second approach: I'm not sure sending headers and a response
body are really different - certainly, at the level of the output
stream, they are not. Adding another execution cycle to process
headers would make the interceptor engine HTTP specific, which we'd
prefer not to do. Further, to the last point listed above, I'm not
sure how general a problem this is. We only know of one instance: SSE.
Traditional long polling with a delayed response but without a
"stretched response as event stream" doesn't present this issue.
Switching a connection from HTTP to WebSockets is similar in that a
request is processed and, at some point, takes over the pipe
completely. I don't think we should generalize alternate processing
cycles until we have more experience with them.

The third approach really turns the current model on its head in the
sense that leave isn't really leave - or it's leave, but at the end I
want to do more stuff. Maybe it would work if the SSE interceptor was
split in two - one to initiate the leave stage and one to be invoked
in that stage to cause things to pause. I don't think it's the right
path.

Here is one other thing to consider: with the exception of CORS, it
isn't clear what other headers would be honored. In fact, to make CORS
work with SSE we need a polyfill that uses XHR. It's clear, then, that
browser's SSE support is a different code path from normal XHR
invocations. Does that path support setting cookies, redirection, etc?
We don't know. In other words, except for CORS, we're not sure what
headers would work with SSE (although we could keep using a polyfill
if necessary). So beyond CORS, how much of a problem is it that we
don't have a totally generic way to set headers in SSE?

All that said, we should implement the hybrid approach described in my
first paragraph above.
