FROM java:8-alpine
MAINTAINER Your Name <you@example.com>

ADD target/tracing-interceptor-0.0.1-SNAPSHOT-standalone.jar /tracing-interceptor/app.jar

EXPOSE 8080

CMD ["java", "-jar", "/tracing-interceptor/app.jar"]
