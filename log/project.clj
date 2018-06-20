; Copyright 2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(defproject io.pedestal/pedestal.log "0.5.5-SNAPSHOT"
  :description "Pedestal logging and metrics facilities"
  :url "https://github.com/pedestal/pedestal"
  :scm "https://github.com/pedestal/pedestal"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 ;; logging
                 [org.slf4j/slf4j-api "1.7.25"]
                 ;; metrics
                 [io.dropwizard.metrics/metrics-core "4.0.2"]
                 [io.dropwizard.metrics/metrics-jmx "4.0.2"]
                 ;; tracing
                 [io.opentracing/opentracing-api "0.31.0"]
                 [io.opentracing/opentracing-util "0.31.0"]]
  :min-lein-version "2.0.0"
  :global-vars {*warn-on-reflection* true}
  :pedantic? :abort

  :aliases {"docs" ["with-profile" "docs" "codox"]}

  :profiles {:docs {:pedantic? :ranges
                    :plugins [[lein-codox "0.9.5"]]}})
