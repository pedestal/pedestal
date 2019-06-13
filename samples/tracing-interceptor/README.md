
# Working with Distributed Tracing using Pedestal's Trace Interceptor

This sample walks through how to use the distributed tracing capabilities
built into Pedestal's Trace Interceptor.

Out of the box, Pedestal's `io.pedestal.log` supports the [OpenTracing API](http://opentracing.io/),
but additional support is possible by extending Pedestal's tracing protocols.

Distributed tracing works by having a centralized server collect portions of
traces called "spans" or "segments" and proving a comprehensive view of
requests and data moving through your service.  A single span can append tags,
data, and log messages during execution, which all get captured during the trace.

If your service forwards calls to additional services, you're able to encode
the trace ID into those requests and continue tracking the trace across boundaries.

**There are some conventions around naming schemes in tracing elements.**
You can read about those [in the OpenTracing Docs](http://opentracing.io/documentation/pages/api/data-conventions.html).


## Running a distributed trace server

We're going to use Docker to run an instance of [Jaeger](https://github.com/jaegertracing/jaeger),
a distributed trace server that supports OpenTracing.

`$ docker run --name jaeger_example --rm -d -p 5775:5775/udp -p 16686:16686 jaegertracing/all-in-one:latest`


## Getting Started

1. Start the application: `lein run`
2. Go to [localhost:8080](http://localhost:8080/) to see: `Hello Tracing World!`
2. Now go to [localhost:8080/trace](http://localhost:8080/trace), which will call another service endpoint, tracing each step.
3. Read your app's source code at src/tracing/service.clj. Explore the routes, functions,
   and tracing interceptor setup.
4. Go to [your local Jaeger UI](http://localhost:16686),
   select "TracingExample" from Service dropdown and click "Find Traces"
5. Click on one of the "TracingExample (4)" tags with a color tab.
6. Explore the trace
7. Stop your service and kill your Jaeger with `$ docker stop jaeger_example`
8. Learn more! See the [Links section below](#links).


## Configuration

To configure logging see config/logback.xml. By default, the app logs to stdout and logs/.
To learn more about configuring Logback, read its [documentation](http://logback.qos.ch/documentation.html).


## How we set our service up to use Jaeger

With our additional dependency, `[io.jaegertracing/jaeger-core "0.27.0"]`,
we can configure our access to our Jaeger server.

The default tracer in Pedestal can be set with a JVM property setting or
an environment variable.
If the default tracer hasn't been registered on startup, it is also possible
to register a tracer with the `-register` protocol function at the main
entry point of your service (for example, in `server.clj`'s `-main` or `run-dev`).


## Developing your service

1. Start a new REPL: `lein repl`
2. Start your service in dev-mode: `(def dev-serv (run-dev))`
3. Connect your editor to the running REPL session.
   Re-evaluated code will be seen immediately in the service.

### [Docker](https://www.docker.com/) container support

1. Build an uberjar of your service: `lein uberjar`
2. Build a Docker image: `sudo docker build -t tracing .`
3. Run your Docker image: `docker run -p 8080:8080 tracing`

### [OSv](http://osv.io/) unikernel support with [Capstan](http://osv.io/capstan/)

1. Build and run your image: `capstan run -f "8080:8080"`

Once the image it built, it's cached.  To delete the image and build a new one:

1. `capstan rmi tracing; capstan build`


## Links
* [Other examples](https://github.com/pedestal/pedestal/tree/master/samples)

