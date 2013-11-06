; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(defproject io.pedestal/pedestal.app "0.3.0-SNAPSHOT"
  :description "Pedestal applications"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-1835"]
                 [org.clojure/core.async "0.1.242.0-44b1e3-alpha"]
                 [ch.qos.logback/logback-classic "1.0.13"]
                 [enlive "1.0.0" :exclusions [org.clojure/clojure]]
                 [domina "1.0.1"]
                 [reiddraper/simple-check "0.5.2"]]
  :test-paths ["test/clj"]
  :profiles {:dev {:source-paths ["dev"]
                   :jvm-opts ["-Xmx1g"]}}
  :aliases {"dumbrepl" ["trampoline" "run" "-m" "clojure.main/main"]})
