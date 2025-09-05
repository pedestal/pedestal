(ns build
  (:require [babashka.fs :as fs]
            [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def src-dir "target/jar-with-spaces")
(def jar-file "test with spaces/jar with spaces.jar")


(defn- spit-file [file content]
  (fs/create-dirs (fs/parent file))
  (spit file content))

(defn create-jar-with-spaces
  "Generate a jar with spaces for tests, adapt as necessary."
  [_]
  (println "Generating test jar:" jar-file)
  (fs/create-dirs src-dir)
  (spit-file (fs/file src-dir "deps.edn") {})
  (spit-file (fs/file src-dir "pedestal" "test" "path with spaces" "some data.edn") {:foo :bar})
  (b/write-pom {:lib 'pedestal-test/jar-with-spaces
                :version "0.0.1"
                :class-dir class-dir
                :basis (b/create-basis {:project (str (fs/file src-dir "deps.edn"))})
                :src-dirs [src-dir]})
  (b/copy-dir {:src-dirs [src-dir]
               :target-dir class-dir})
  (fs/create-dirs "test-with spaces" )
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))
