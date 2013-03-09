(defproject io.pedestal/pedestal.jetty "0.0.1-SNAPSHOT"
  :description "Embedded Jetty adapter for Pedestal HTTP Service"
  :dependencies [[org.clojure/clojure "1.5.0-RC16"]
                 [org.eclipse.jetty/jetty-server "8.1.9.v20130131"]
                 [org.eclipse.jetty/jetty-servlet "8.1.9.v20130131"]
                 [javax.servlet/javax.servlet-api "3.0.1"]]
  :min-lein-version "2.0.0"
  :warn-on-reflection true)
