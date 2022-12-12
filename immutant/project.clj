; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(defproject io.pedestal/pedestal.immutant "0.5.11-SNAPSHOT"
  :description "Embedded Immutant adapter for Pedestal HTTP Service"
  :url "https://github.com/pedestal/pedestal"
  :scm "https://github.com/pedestal/pedestal"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [potemkin "0.4.5"]
                 [org.jboss.logging/jboss-logging "3.4.1.Final"]
                 [org.immutant/web "2.1.10" :exclusions  [org.jboss.logging/jboss-logging]]
                 ;; immutant is using outdated wunderboss version which have security issues
                 [org.projectodd.wunderboss/wunderboss-core "0.13.1" :exclusions [io.undertow/undertow-servlet
                                                                                  io.undertow/undertow-core
                                                                                  ch.qos.logback/logback-classic]]
                 [org.projectodd.wunderboss/wunderboss-clojure "0.13.1"]
                 [org.projectodd.wunderboss/wunderboss-web-undertow "0.13.1"]
                 [io.undertow/undertow-core "2.2.14.Final"]
                 [io.undertow/undertow-servlet "2.2.14.Final"]
                 [io.undertow/undertow-websockets-jsr "2.2.14.Final"]
                 ;; immutant is pulling libs with security issues, bump these
                 [commons-fileupload "1.3.3"]
                 [commons-io "2.7"]
                 [javax.servlet/javax.servlet-api "3.1.0"]]
  :min-lein-version "2.0.0"
  :global-vars {*warn-on-reflection* true}
  :pedantic? :abort

  :aliases {"docs" ["with-profile" "docs" "codox"]}

  :profiles {:docs {:pedantic? :ranges
                    :plugins [[lein-codox "0.9.5"]]}})
