# fast-pedestal

This sample illustrates various techniques to reduce latency and increase throughput/goodput.

In general:

 * Isolate and accurately measure/profile before making any change to your system
  * When profiling, do not run through leiningen.  Build an Uberjar and control all JVM options.
  * Warm the application up before taking measurements - run your scenario a handful of times
  * Use **REAL** data and workloads.  Artificial benching will give you artificial results.
 * Interceptors and business logic tend to dominate processing time
  * Setup the ideal, minimal interceptor chain for your application
 * Tune logging and metrics reporting appropriate for the deployment
 * Tune the JVM appropriately for the deployment
 * Prefer using static routes (No wildcard or URL param routes.  Parameters only via query-string/POST data)
 * Avoid throwing exceptions (especially in interceptors)
  * Everything in Pedestal is an interceptor :)
 * Consider writing a custom Chain Provider - choose the best platform/container for your application
 * If you're proxying the call, go async (return a channel), and use an NIO body (go async down to the wire)
 * Avoid NIO bodies for small, static, intant responses.  Stay synchronous
 * Use the entire stack
  * If you're on an application container, consider other Java web technologies (for example, ServletFilters)
 * If you need a `resources` interceptor, use `io.pedestal.http.ring-middlewares.fast-resource`,
   which optimizes responses based on the HTTP buffer

## Configuration

To configure logging see config/logback.xml. By default, the app logs to stdout and logs/.
To learn more about configuring Logback, read its [documentation](http://logback.qos.ch/documentation.html).

## Links
* [Other examples](https://github.com/pedestal/samples)

