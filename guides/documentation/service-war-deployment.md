# Deploying a WAR File

Java web applications are commonly deployed to *web containers*, also
called Servlet containers or application servers. Web containers
manage the runtime environment and lifecycle of an application.
Popular web containers include [Jetty], [Tomcat], and [JBoss]. Some
hosting services such as [AWS Elastic Beanstalk] also function as web
containers.

To deploy an application to a web container, you must package it as a
[Web Archive](http://docs.oracle.com/javaee/6/tutorial/doc/bnaby.html)
(WAR) file. A WAR file is just a Java [JAR] file with
a particular internal layout and a `.war` extension.

[Jetty]: http://www.eclipse.org/jetty/
[Tomcat]: http://tomcat.apache.org/
[JBoss]: http://www.jboss.org/
[AWS Elastic Beanstalk]: http://aws.amazon.com/elasticbeanstalk/
[JAR]: http://docs.oracle.com/javase/tutorial/deployment/jar/


## Building a WAR File

**In the future, Pedestal will include automated tools** to accomplish
this, but for now you can follow these steps to build a WAR file:


### Step One: Prepare Your Project

A WAR file contains all the dependencies of your application, but it
should **not** contain any dependencies which would conflict with the
web container itself.

By default, new Pedestal applications have dependencies declared for
running an "embedded" web container, either Tomcat or Jetty. You need
to remove those lines before building the WAR.

Edit your `project.clj` file, find the vector after `:dependencies`,
and **delete** the following entries (if they exist):

    [io.pedestal/pedestal.jetty "0.1.0"]
    [io.pedestal/pedestal.tomcat "0.1.0"]

### Step Two: Configure Your Servlets

A WAR file must contain a special file called `web.xml` which tells
the web container how to configure the application.

In the root directory of your project, create a file called `web.xml`
like the following example:

    <?xml version="1.0" encoding="UTF-8"?>
    <web-app xmlns="http://java.sun.com/xml/ns/javaee"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd"
             version="3.0"
             metadata-complete="true">
      <description>Pedestal HTTP Servlet</description>
      <display-name>Pedestal HTTP Servlet</display-name>
      <servlet>
        <servlet-name>PedestalServlet</servlet-name>
        <servlet-class>io.pedestal.servlet.ClojureVarServlet</servlet-class>
        <init-param>
          <param-name>init</param-name>
          <param-value>YOUR_APP_SERVER_NAMESPACE/servlet-init</param-value>
        </init-param>
        <init-param>
          <param-name>service</param-name>
          <param-value>YOUR_APP_SERVER_NAMESPACE/servlet-service</param-value>
        </init-param>
        <init-param>
          <param-name>destroy</param-name>
          <param-value>YOUR_APP_SERVER_NAMESPACE/servlet-destroy</param-value>
        </init-param>
      </servlet>
      <servlet-mapping>
        <servlet-name>PedestalServlet</servlet-name>
        <url-pattern>/*</url-pattern>
      </servlet-mapping>
    </web-app>

Replace `YOUR_APP_SERVER_NAMESPACE` with the "server" namespace
generated for your application by the Pedestal template. If your app
is called "foo" then this would be `foo.server`. The
template-generated source file at `src/foo/server.clj` should contain
functions named `servlet-init`, `servlet-destroy`, and
`servlet-service`.

**Note:** The `url-pattern` in the XML above must match the routes
your Pedestal application is expected to handle **and** must match the
conventions of your web container. For example, if you are deploying
an application in the file `foo.war`, a typical web container will
expect it to have `<url-pattern>/foo/*</url-pattern>`. Therefore your
application should have routes that begin with `/foo/`.

If your application will be deployed as the "root" web application in
the container, then you should have `<url-pattern>/*</url-pattern>` as
shown in the example above. Consult the documentation of your web
container for more details on Servlet mappings.


### Step Three: Build the WAR

Working from a standard Unix shell (or Cygwin on Windows), run the
following commands in your project directory.

You will need both [Leiningen] and [Maven] installed to run these
commands.

[Leiningen]: https://github.com/technomancy/leiningen
[Maven]: http://maven.apache.org/

Getting dependencies:

    lein clean
    lein pom
    mvn dependency:copy-dependencies -DoutputDirectory=target/war/WEB-INF/lib

Copying your application files:

    mkdir -p target/war/WEB-INF/classes
    cp -R src/* config/* target/war/WEB-INF/classes

Copying `web.xml`:

    cp web.xml target/war/WEB-INF

Creating the WAR file:

    jar cvf target/YOUR_APPLICATION.war -C target/war WEB-INF

Replace `YOUR_APPLICATION` with the name of your application.


### Step Four: Deploy

Consult the documentation of your web container to find out how to
deploy an application from a WAR file. For many web containers, it may
be a simple matter of copying the `.war` file into a "webapps"
directory.
