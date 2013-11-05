; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(defproject io.pedestal/pedestal.tomcat "0.2.3-SNAPSHOT"
  :description "Embedded Tomcat adapter for Pedestal HTTP Service"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.apache.tomcat.embed/tomcat-embed-logging-juli "7.0.30"]
                 [org.apache.tomcat.embed/tomcat-embed-jasper "7.0.30"]
                 [org.apache.tomcat.embed/tomcat-embed-core "7.0.30"]
                 [javax.servlet/javax.servlet-api "3.0.1"]]
  :min-lein-version "2.0.0"
  :global-vars {*warn-on-reflection* true})
