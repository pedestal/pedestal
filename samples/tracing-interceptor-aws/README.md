
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

We're going to use Docker to run an instance of the [X-Ray Daemon](https://docs.aws.amazon.com/xray/latest/devguide/xray-daemon-local.html),
which provides distributed tracing for us in AWS.

`$ docker run --name aws_xray --rm -p 2000:2000/udp \
--attach STDOUT \
-e AWS_ACCESS_KEY_ID=aws_access_key \
-e AWS_SECRET_ACCESS_KEY=aws_secret_key \
-e AWS_REGION=aws_region \
namshi/aws_xray --local-mode`


## Getting Started

1. Start the application: `PEDESTAL_TRACER=io.pedestal.log.aws.xray/tracer lein run`
2. Go to [localhost:8080](http://localhost:8080/) to see: `Hello Tracing World!`
2. Now go to [localhost:8080/trace](http://localhost:8080/trace), which will call another service endpoint, tracing each step.
3. Read your app's source code at src/tracing/service.clj. Explore the routes, functions,
   and tracing interceptor setup.
4. Go to [your X-Ray UI](https://console.aws.amazon.com/xray/home) and explore the traces
7. Stop your service and kill your X-Ray Container with Ctrl-c
8. Learn more! See the [Links section below](#links).


## Configuration

To configure logging see config/logback.xml. By default, the app logs to stdout and logs/.
To learn more about configuring Logback, read its [documentation](http://logback.qos.ch/documentation.html).


## How we set our service up to use X-Ray

With our additional dependency, `[io.pedestal/pedestal.aws "0.5.4-SNAPSHOT"]`,
we can configure our access to our local X-Ray Daemon.

The default tracer in Pedestal can be set with a JVM property setting or
an environment variable.

We used the environment variable to set the Tracer to X-Ray.

It is also possible to register a tracer with the `-register` protocol function at the main
entry point of your service (for example, in `server.clj`'s `-main` or `run-dev`),
although this is tricky for non-OpenTracing systems.


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
* [Other examples](https://github.com/pedestal/samples)

