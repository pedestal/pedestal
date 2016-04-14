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

(defproject io.pedestal/pedestal.jetty "0.4.2-SNAPSHOT"
  :description "Embedded Jetty adapter for Pedestal HTTP Service"
  :url "https://github.com/pedestal/pedestal"
  :scm "https://github.com/pedestal/pedestal"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.eclipse.jetty/jetty-server "9.3.8.v20160314"]
                 [org.eclipse.jetty/jetty-alpn-server "9.3.8.v20160314"]
                 [org.eclipse.jetty.alpn/alpn-api "1.1.2.v20150522"]
                 [org.eclipse.jetty.http2/http2-server "9.3.8.v20160314"]
                 [org.eclipse.jetty/jetty-servlet "9.3.8.v20160314"]
                 [javax.servlet/javax.servlet-api "3.1.0"]]
  :min-lein-version "2.0.0"
  :global-vars {*warn-on-reflection* true})

