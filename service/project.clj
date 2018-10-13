; Copyright 2013 Relevance, Inc.
; Copyright 2014-2018 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(let [dir                   (clojure.string/join "/"
                                                 (butlast (clojure.string/split *file* #"/")))
      all-deps              (clojure.edn/read-string (slurp (clojure.java.io/file dir "deps.edn")))
      dep-formatter         (fn [deps]
                              (reduce (fn [acc [k v]]
                                        (if-let [exclude (:exclusions v)]
                                          (conj acc [k (:mvn/version v) :exclusions exclude])
                                          (conj acc [k (:mvn/version v)])))
                                      []
                                      deps))
      repo-formatter        (fn [repos]
                              (reduce (fn [acc [k v]]
                                        (conj acc[k v]))
                                      []
                                      repos))
      release-deps          (get-in all-deps [:aliases :release :extra-deps])
      dev-lein-deps         (get-in all-deps [:aliases :dev-lein :extra-deps])
      dev-deps              (get-in all-deps [:aliases :dev :extra-deps])
      dev-paths             (get-in all-deps [:aliases :dev :extra-paths])
      repos             (:mvn/repos all-deps)
      repos-vec-vec     (repo-formatter repos)
      dev-deps-vec-vec      (dep-formatter (merge dev-deps dev-lein-deps))
      deps-vec-vec          (->> all-deps
                                 :deps
                                 (merge release-deps)
                                 dep-formatter
                                 (into ['[org.clojure/clojure "1.9.0"]]))
      provided-deps         (get-in all-deps [:aliases :provided :extra-deps])
      provided-deps-vec-vec (dep-formatter provided-deps)]
  (defproject io.pedestal/pedestal.service "0.5.5-SNAPSHOT"
    :description "Pedestal Service"
    :url "https://github.com/pedestal/pedestal"
    :scm "https://github.com/pedestal/pedestal"
    :license {:name "Eclipse Public License"
              :url  "http://www.eclipse.org/legal/epl-v10.html"}
    :dependencies ~deps-vec-vec
    :min-lein-version "2.0.0"
    :java-source-paths ["java"]
    :javac-options ["-target" "1.8" "-source" "1.8"]
    :jvm-opts ["-D\"clojure.compiler.direct-linking=true\""]
    :global-vars {*warn-on-reflection* true}
    :pedantic? :abort
    :aliases {"bench-log"     ["trampoline" "run" "-m" "io.pedestal.log-bench"]
              "bench-service" ["trampoline" "run" "-m" "io.pedestal.niotooling.server"]
              "bench-route"   ["trampoline" "run" "-m" "io.pedestal.route.route-bench"]
              "dumbrepl"      ["trampoline" "run" "-m" "clojure.main/main"]
              "docs"          ["with-profile" "docs" "codox"]}
    :profiles {:default  [:dev :provided :user :base]
               :provided {:dependencies ~provided-deps-vec-vec}
               :dev      {:source-paths ~dev-paths
                          :dependencies ~dev-deps-vec-vec
                          :repositories ~repos-vec-vec}
               :docs {:pedantic?    :ranges
                      :plugins      [[lein-codox "0.9.5"]]
                      :dependencies ~provided-deps-vec-vec}}
                                        ;:jvm-opts ^:replace ["-D\"clojure.compiler.direct-linking=true\""
                                        ;                     "-d64" "-server"
                                        ;                     "-Xms1g"                             ;"-Xmx1g"
                                        ;                     "-XX:+UnlockCommercialFeatures"      ;"-XX:+FlightRecorder"
                                        ;                     ;"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8030"
                                        ;                     "-XX:+UseG1GC"
                                        ;                     ;"-XX:+UseConcMarkSweepGC" "-XX:+UseParNewGC" "-XX:+CMSParallelRemarkEnabled"
                                        ;                     ;"-XX:+ExplicitGCInvokesConcurrent"
                                        ;                     "-XX:+AggressiveOpts"
                                        ;                     ;-XX:+UseLargePages
                                        ;                     "-XX:+UseCompressedOops"]
    ))
