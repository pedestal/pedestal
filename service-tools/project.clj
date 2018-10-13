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

(let [dir           (clojure.string/join "/"
                                         (butlast (clojure.string/split *file* #"/")))
      all-deps      (clojure.edn/read-string (slurp (clojure.java.io/file dir "deps.edn")))
      dep-formatter (fn [deps]
                      (reduce (fn [acc [k v]]
                                (if-let [exclude (:exclusions v)]
                                  (conj acc [k (:mvn/version v) :exclusions exclude])
                                  (conj acc [k (:mvn/version v)])))
                              []
                              deps))
      release-deps  (get-in all-deps [:aliases :release :extra-deps])
      deps-vec-vec  (->> all-deps
                         :deps
                         (merge release-deps)
                         dep-formatter
                         (into ['[org.clojure/clojure "1.9.0"]]))
      lein-dev-deps     (get-in all-deps [:aliases :lein-dev :extra-deps])
      lein-dev-deps-vec-vec (dep-formatter lein-dev-deps)]
  (defproject io.pedestal/pedestal.service-tools "0.5.5-SNAPSHOT"
    :description "Pedestal tools for service development"
    :url "https://github.com/pedestal/pedestal"
    :scm "https://github.com/pedestal/pedestal"
    :license {:name "Eclipse Public License"
              :url  "http://www.eclipse.org/legal/epl-v10.html"}
    :min-lein-version "2.0.0"
    :dependencies ~deps-vec-vec

    :aliases {"docs" ["with-profile" "docs" "codox"]}

    :pedantic? :abort

    :profiles {:dev {:dependencies ~lein-dev-deps-vec-vec}
               :docs {:pedantic? :ranges
                      :plugins   [[lein-codox "0.9.5"]]}}))
