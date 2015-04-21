# jboss

This sample demonstrates how to deploy a service in jboss using
immutant. The sample can run on jetty as well. Routing and URL
generation work correctly in both cases.

## Getting Started

1. Start the application: `lein run`
2. Go to [localhost:8080](http://localhost:8080) to see how the route
is resolved.
3. Go to [localhost:8080/about](http://localhost:8080/about) to see
how the route is resolved.
4. Stop the server.
5. Install the immutant container: 'lein immutant install'
6. Run the immutant container: 'lein immutant run'
7. In a separate shell, deploy the sample to the immutant container: 'lein immutant deploy'
8. Watch the immutant log to see when the sample is completely
deployed.
9. Go to [localhost:8080](http://localhost:8080/foo) to see how the route
is resolved.
10. Go to [localhost:8080/about](http://localhost:8080/foo/about) to see
how the route is resolved.
11. Read the service's source code at src/jboss/service.clj. Read
immutant initialization code at src/immutant/init.clj. 
12. Learn more! See the [Links section below](#links).

## Links
* [Other examples](https://github.com/pedestal/samples)
