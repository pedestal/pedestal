; Copyright 2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(defproject io.pedestal/pedestal.interceptor "0.5.5-SNAPSHOT"
  :description "Pedestal interceptor chain and execution utilities"
  :url "https://github.com/pedestal/pedestal"
  :scm "https://github.com/pedestal/pedestal"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.async "0.4.474" :exclusions [org.clojure/tools.analyzer.jvm]]
                 [io.pedestal/pedestal.log "0.5.5-SNAPSHOT"]

                 ;; Error interceptor tooling
                 [org.clojure/core.match "0.3.0-alpha5" :exclusions [[org.clojure/clojurescript]
                                                                     [org.clojure/tools.analyzer.jvm]]]
                 [org.clojure/tools.analyzer.jvm "0.7.2"]]
  :min-lein-version "2.0.0"
  :pedantic? :abort
  :global-vars {*warn-on-reflection* true}

  :aliases {"docs" ["with-profile" "docs" "codox"]}

  :profiles {:docs {:pedantic? :ranges
                    :plugins [[lein-codox "0.9.5"]]}})
