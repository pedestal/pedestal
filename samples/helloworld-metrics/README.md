# helloworld-metrics

This sample demonstrates how to use metrics. The default web page triggers
built-in four tyes of metrics which can be seen on JDK's jvisualvm. Another web page
triggers statsd's counter metric. We can see the metric result by nc (netcat) command.

## Getting Started

### jvisualvm with MBeans plugin

If you have JDK installed, you already have jvidualvm. However, by default,
MBeans plugin is not installed.

1. Start by hitting `jvisualvm` command
2. Tools -> Plugins -> Available Plugins
3. Select VisualVM-MBeans and install

Alternatively, you may use jconsole which should have MBeans feature out of the box.

### Built-in Metrics Application

1. Start the application: `lein run-dev` \*
2. Go to [localhost:8080](http://localhost:8080/) to see: `Hello World!`
3. Metrics will show up on jvisualvm's MBeans tab. ![](./jvisualvm-with-mbeans.png =400x)
4. Reload the page multiple times and click Refresh button on the bottom of right pane.
5. Read your app's source code at src/helloworld_metrics/service.clj,
   especially, home-page function.
6. Learn more! See the [Links section below](#links).

### Statsd Metrics Application

1. Start nc (netcat) command to listen UDP on port 8125

    ```
    $ nc -kul 8125
    ```

2. If necessary, start the application: `lein run-dev` \*
3. Go to [localhost:8080/statsd](http://localhost:8080/statsd) and reload the page multiple times
4. Hit return on the terminal `nc` is running. You'll see the message something like:

    ```
    helloworld-counter-04/05/2016:1|g
    helloworld-counter-04/05/2016:4|g
    ```

    You can see how many times the page has been requested.
5. Read your app's source code at src/helloworld_metrics/service.clj,
   especially, statsd-page and related functions.
6. Learn more! See the [Links section below](#links).


\* `lein run-dev` automatically detects code changes. Alternatively, you can run in production mode
with `lein run`.


## Configuration

To configure logging see config/logback.xml. By default, the app logs to stdout and logs/.
To learn more about configuring Logback, read its [documentation](http://logback.qos.ch/documentation.html).

## Links
* [Other examples](https://github.com/pedestal/samples)

