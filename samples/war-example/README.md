# Building a WAR file

This sample shows the necessary tooling and options for building a deployable
WAR file.

Note that developing at the REPL with this sample is a bit different
than usual. You must tell Pedestal what HTTP server to use. See the
section "Developing Your Service" below for an example.

**Pedestal requires Java 1.8+ and Servlet 3.1.0 support**


## Building a WAR with [`lein-pedestal`](https://github.com/ohpauleez/lein-pedestal)

1. Remove the container dependency (e.g. Jetty) from the `project.clj` or move
   it to a dedicated profile.  Add a `:test` dependency on Servlet-3.1.
2. Uncomment the WAR code changes in [`server.clj`](https://github.com/pedestal/pedestal/blob/master/samples/war-example/src/war_example/server.clj#L39-L53)
3. Add the [latest lein-pedestal](https://clojars.org/ohpauleez/lein-pedestal)
   to the `:plugins` section in your `project.clj` file.
4. Set all WAR setting in the `:pedestal` section within your `project.clj` file.
 * You must supply the `:server-ns` - this should be the namespace (as a string)
   where your `servlet-*` functions are located.
5. `lein pedestal uberwar`
6. Copy your uberwar to you /webapps directory on your container: `cp target/war-example-0.0.1-SNAPSHOT-standalone.war /mycontainer/webapps`
7. Startup your container locally
8. Browse to the [root page](http://127.0.0.1:8080/war-example-0.0.1-SNAPSHOT-standalone/)
   and then try the [about page](http://127.0.0.1:8080/war-example-0.0.1-SNAPSHOT-standalone/about)


## Building a WAR at the REPL

TODO

## Getting Started with local development, not as a WAR

1. Start the application: `lein run-dev`
2. Go to [localhost:8080](http://localhost:8080/) to see: `Hello World!`
3. Read your app's source code at src/war_example/service.clj. Explore the docs of functions
   that define routes and responses.
4. Run your app's tests with `lein test`. Read the tests at `test/war_example/service_test.clj`.
5. Learn more! See the [Links section below](#links).


## Configuration

To configure logging see config/logback.xml. By default, the app logs to stdout and logs/.
To learn more about configuring Logback, read its [documentation](http://logback.qos.ch/documentation.html).


## Developing your service

This project is set up to exclude the HTTP server from the runtime
dependencies. That way you don't accidentally deploy all of Jetty as
jar files inside a Tomcat container! To run in dev mode, you need to
tell Pedestal which HTTP server to run. `project.clj` has a profile
called "jetty" that adds a dependency on Jetty. With that profile, you
can do the usual REPL based development, but Jetty's jars _won't_ be
included in the war file you create.

1. Start a new REPL: `lein with-profile +jetty repl`
2. Start your service in dev-mode: `(def dev-serv (run-dev))`
3. Connect your editor to the running REPL session.
   Re-evaluated code will be seen immediately in the service.

### [Docker](https://www.docker.com/) container support

1. Build an uberjar of your service: `lein uberjar`
2. Build a Docker image: `sudo docker build -t war-example .`
3. Run your Docker image: `docker run -p 8080:8080 war-example`

### [OSv](http://osv.io/) unikernel support with [Capstan](http://osv.io/capstan/)

1. Build and run your image: `capstan run -f "8080:8080"`

Once the image it built, it's cached.  To delete the image and build a new one:

1. `capstan rmi war-example; capstan build`


## Links
* [Other examples](https://github.com/pedestal/samples)
