# override-logging

This sample demonstrates how to configure an override logger
for use with the logging macros defined in  `pedestal.log`.

The override logger is defined in the `override-logger.log` namespace and
the implementation is for demonstration purposes only. It does not respect
the logback configuration and sends all log messages to `tap>`. The `println`
function is added as a tap so that all messages are printed to the console.

## Getting Started

1. Start the application: `lein run`
2. Go to [localhost:8080](http://localhost:8080/) then check the console. The
   console will contain log messages created by Pedestal's default logger.
3. Restart the application with override logging enabled: `PEDESTAL_LOGGER=override-logging.log/make-logger lein run`
4. Go to [localhost:8080](http://localhost:8080/) then check the console. The
   console will now contain log messages created by the override logger. These
   log messages will be maps containing the key/value pair `:logger OverrideLogger`.
5. Create an uber jar: `lein do clean, uberjar`.
6. Start the application from the uberjar: `java -jar target/override-logging-0.0.1-SNAPSHOT-standalone.jar`
7. Go to [localhost:8080](http://localhost:8080/) then check the console. The
   console will contain log messages created by Pedestal's default logger.
8. Restart the application from the uberjar with override logging enabled:
   `java -Dio.pedestal.log.overrideLogger=override-logging.log/make-logger -jar target/override-logging-0.0.1-SNAPSHOT-standalone.jar`
9. Go to [localhost:8080](http://localhost:8080/) then check the console. The
   console will now contain log messages created by the override logger.
10. Read your app's source code at `src/override_logging/service.clj`,
   `src/override_logging/service_impl.clj` and `src/override_logging/log.clj` to
   understand who to override logging.
11. Review how the applicaiton entry point (`main`) is defined in `project.clj`
    for the `:dev` and `:uberjar` profiles.
