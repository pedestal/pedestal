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

(defproject io.pedestal/pedestal "0.5.5-SNAPSHOT"
  :plugins [[lein-sub "0.2.3"]]
  :sub ["log"
        "interceptor"
        "route"
        "service"
        "jetty"
        "immutant"
        "tomcat"
        "aws"
        "service-tools"
        "service-template"]
  :aliases {"docs" ["with-profile" "docs" "codox"]}
  :profiles {:docs {:plugins [[lein-codox "0.9.5"]]
                    :dependencies [[io.pedestal/pedestal.log "0.5.5-SNAPSHOT"]
                                   [io.pedestal/pedestal.interceptor "0.5.5-SNAPSHOT"]
                                   [io.pedestal/pedestal.route "0.5.5-SNAPSHOT"]
                                   [io.pedestal/pedestal.service "0.5.5-SNAPSHOT"]
                                   [io.pedestal/pedestal.jetty "0.5.5-SNAPSHOT"]
                                   [io.pedestal/pedestal.immutant "0.5.5-SNAPSHOT"]
                                   [io.pedestal/pedestal.tomcat "0.5.5-SNAPSHOT"]
                                   [io.pedestal/pedestal.aws "0.5.5-SNAPSHOT"]
                                   [io.pedestal/pedestal.service-tools "0.5.5-SNAPSHOT"]]
                    :codox {:output-path "codox"
                            :source-uri "http://github.com/pedestal/pedestal/blob/{version}/{filepath}#L{line}"
                            :source-paths ["log/src"
                                           "interceptor/src"
                                           "route/src"
                                           "service/src"
                                           "jetty/src"
                                           "immutant/src"
                                           "tomcat/src"
                                           "aws/src"
                                           "service-tools/src"]}}})
