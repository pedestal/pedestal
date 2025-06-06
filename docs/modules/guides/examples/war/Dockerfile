FROM jetty:12.0.22-jdk21-alpine-amazoncorretto
RUN java -jar /usr/local/jetty/start.jar --add-modules=ee10-deploy
ADD target/app.war /var/lib/jetty/webapps/ROOT.war
