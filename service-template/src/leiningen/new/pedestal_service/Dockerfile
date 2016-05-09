FROM java:8-alpine
MAINTAINER Your Name <you@example.com>

ADD target/{{name}}-0.0.1-SNAPSHOT-standalone.jar /{{name}}/app.jar

EXPOSE 8080

CMD ["java", "-jar", "/{{name}}/app.jar"]
