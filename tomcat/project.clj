; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(defproject io.pedestal/pedestal.tomcat "0.5.4-SNAPSHOT"
  :description "Embedded Tomcat adapter for Pedestal HTTP Service"
  :url "https://github.com/pedestal/pedestal"
  :scm "https://github.com/pedestal/pedestal"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.apache.tomcat.embed/tomcat-embed-logging-juli "8.0.52"]
                 [org.apache.tomcat.embed/tomcat-embed-jasper "9.0.8"]
                 [org.apache.tomcat.embed/tomcat-embed-core "9.0.8"]
                 [javax.servlet/javax.servlet-api "3.1.0"]]
  :min-lein-version "2.0.0"
  :global-vars {*warn-on-reflection* true}
  :pedantic? :abort

  :aliases {"docs" ["with-profile" "docs" "codox"]}

  :profiles {:docs {:pedantic? :ranges
                    :plugins [[lein-codox "0.9.5"]]}})
