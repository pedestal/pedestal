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
