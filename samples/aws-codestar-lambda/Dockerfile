FROM java:8-alpine
MAINTAINER Your Name <you@example.com>

ADD target/pedestal-lambda-0.0.1-SNAPSHOT-standalone.jar /pedestal-lambda/app.jar

EXPOSE 8080

CMD ["java", "-jar", "/pedestal-lambda/app.jar"]
