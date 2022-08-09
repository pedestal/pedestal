; Copyright 2013 Relevance, Inc.
; Copyright 2014-2019 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(defproject io.pedestal/pedestal.jetty "0.5.11-SNAPSHOT"
  :description "Embedded Jetty adapter for Pedestal HTTP Service"
  :url "https://github.com/pedestal/pedestal"
  :scm "https://github.com/pedestal/pedestal"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.5.648"]
                 [org.slf4j/slf4j-api "1.7.36"]
                 [io.pedestal/pedestal.log "0.5.10"]
                 [org.ow2.asm/asm "9.3"]
                 [org.eclipse.jetty/jetty-server "10.0.11" :exclusions [org.slf4j/slf4j-api]]
                 [org.eclipse.jetty/jetty-servlet "10.0.11" :exclusions [org.slf4j/slf4j-api]]
                 [org.eclipse.jetty.alpn/alpn-api "1.1.3.v20160715"]
                 [org.eclipse.jetty/jetty-alpn-server "10.0.11" :exclusions [org.slf4j/slf4j-api]]
                 [org.eclipse.jetty.http2/http2-server "10.0.11" :exclusions [org.slf4j/slf4j-api]]
                 [org.eclipse.jetty.websocket/websocket-servlet "10.0.11" :exclusions [org.slf4j/slf4j-api]]
                 [org.eclipse.jetty.websocket/websocket-jetty-server "10.0.11" :exclusions [org.slf4j/slf4j-api]]
                 [javax.servlet/javax.servlet-api "4.0.1"]]
  :min-lein-version "2.0.0"
  :global-vars {*warn-on-reflection* true}
  :pedantic? :abort

  :aliases {"docs" ["with-profile" "docs" "codox"]}

  :profiles {:docs {:pedantic? :ranges
                    :plugins [[lein-codox "0.9.5"]]}})
