(defproject io.pedestal/pedestal.tomcat "0.0.1-SNAPSHOT"
  :description "Embedded Tomcat adapter for Pedestal HTTP Service"
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [org.apache.tomcat.embed/tomcat-embed-logging-juli "7.0.30"]
                 [org.apache.tomcat.embed/tomcat-embed-jasper "7.0.30"]
                 [org.apache.tomcat.embed/tomcat-embed-core "7.0.30"]
                 [javax.servlet/javax.servlet-api "3.0.1"]]
  :min-lein-version "2.0.0"
  :warn-on-reflection true)
