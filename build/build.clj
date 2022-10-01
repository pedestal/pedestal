; Copyright 2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns build
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [net.lewisship.build :refer [requiring-invoke]]
            [clojure.tools.build.api :as b]))

(def version-file "VERSION.txt")
(def project-name 'io.pedestal)
(def version (-> version-file slurp str/trim))

(def module-dirs
  ;; Keep these in dependency order
  (str/split "log interceptor route service aws immutant jetty service-tools tomcat"
             #" "))

;; Working around this problem (bug)?
;; Manifest type not detected when finding deps for io.pedestal/pedestal.log in coordinate #:local{:root "../log"}
;; Basically, not recognizing relative paths correctly; I think they are being evaluated at the top level, so ".." is the
;; directory about the pedestal workspace.
;; See https://clojure.atlassian.net/browse/TDEPS-106

(defn- classpath-for
  [dir overrides]
  (binding [b/*project-root* dir]
    (println "Reading" dir "...")
    (let [basis (b/create-basis {:override-deps overrides}) roots (:classpath-roots basis)]
      (map (fn [path]
             (if (str/starts-with? path "/")
               path
               (str dir "/" path)))
           roots))))

(defn- canonical
  "Expands a relative path to a full path."
  [path]
  (.getAbsolutePath (io/file path)))

(defn- as-override
  [coll dir-name]
  (let [project-name (symbol "io.pedestal" (str "pedestal." dir-name))
        project-dir (canonical dir-name)]
    (assoc coll project-name {:local/root project-dir})))

(defn codox
  "Generates combined Codox documentation for all sub-projects."
  [_]
  (let [overrides (reduce as-override {} module-dirs)
        project-classpath (mapcat #(classpath-for % overrides) module-dirs)
        codox-classpath (:classpath-roots (b/create-basis {:aliases [:codox]}))
        full-classpath (->> project-classpath
                            (concat codox-classpath)
                            distinct
                            sort)
        codox-config {:metadata {:doc/format :markdown}
                      :name (str (name project-name) " libraries")
                      :version version
                      :source-paths (mapv #(str % "/src") module-dirs)
                      :source-uri "https://github.com/pedestal/pedestal/blob/{version}/{filepath}#L{line}"}
        expression `(do
                      ((requiring-resolve 'codox.main/generate-docs) ~codox-config)
                      ;; Above returns the output directory name, "target/doc", which gets printed
                      ;; by clojure.main, so override that to nil on success here.
                      nil)
        ;; The API version mistakenly requires :basis, so bypass it.
        process-params (requiring-invoke clojure.tools.build.tasks.process/java-command
                                         {:cp full-classpath
                                          :main "clojure.main"
                                          :main-args ["--eval" (pr-str expression)]})
        _ (println "Starting codox ...")
        {:keys [exit]} (b/process process-params)]
    (when-not (zero? exit)
      (println "Codox process exited with status:" exit)
      (System/exit exit))))

(defn deploy-all
  "Builds and deploys all sub-modules.

  :dry-run - install to local Maven repository, but do not deploy to remote."
  [{:keys [dry-run]}]
  (println "Deploying version" version "...")
  (doseq [dir module-dirs]
    (println dir "...")
    (binding [b/*project-root* dir]
      (let [basis (b/create-basis)
            project-name (symbol "io.pedestal" (str "pedestal." dir))
            class-dir "target/classes"
            output-file (format "target/pedestal.%s-%s.jar" dir version)]
        (b/delete {:path "target"})
        (when (= "service" dir)
          ;; service is the only module that has Java compilation.
          (let [{:keys [exit]} (b/process {:command-args ["clojure" "-T:build" "compile-java"]})]
            (when-not (zero? exit)
              (println "Compilation failed with status:" exit)
              (System/exit exit))))
        (b/write-pom {:class-dir class-dir
                      :lib project-name
                      :version version
                      :basis basis
                      :scm {:url (str "https://github.com/pedestal/" dir)}})
        (b/copy-dir {:src-dirs ["src" "resources"]
                     :target-dir class-dir})
        (b/jar {:class-dir class-dir
                :jar-file output-file})
        ;; Install it locally, so later dirs can find it. This ensures that the
        ;; intra-project dependencies are correct in the generated POM files.
        (b/install {:basis basis
                    :lib project-name
                    :version version
                    :jar-file output-file
                    :class-dir class-dir})
        (when-not dry-run
          ;; Deploy part goes here
          ))))
  ;; TODO: That leiningen service-template
  )


(defn update-version
  "Updates the version of the library.

  This changes the root VERSION.txt file and edits all deps.edn files to reflect the new version as well.

  It does not commit the change."
  [{:keys [version snapshot]}]
  (let [version' (if snapshot
                   (str version "-SNAPSHOT")
                   version)]
    (doseq [dir module-dirs]
      (println "Updating" dir "...")
      (requiring-invoke io.pedestal.build/update-version-in-deps dir version'))

    ;; TODO: Do something for the lein service-template
    ;; Maybe update some of the docs as well?

    (b/write-file {:path version-file
                   :string version'})

    (println "Updated to version:" version')))

(defn- parse-version
  [version]
  (let [[_ major minor patch snapshot :as match] (re-matches #"(?ix)
                                                    (\d+)     # major
                                                    \. (\d+)  # minor
                                                    \. (\d+)  # patch
                                                    (\-SNAPSHOT)?"
                                                             version)]
    (when-not match
      (throw (RuntimeException. (format "Version '%s' is not parsable" version))))
    {:major (parse-long major)
     :minor (parse-long minor)
     :patch (parse-long patch)
     :snapshot? (some? snapshot)}))

(defn- advance
  [version-data kind]
  (case kind
    :major (-> version-data
               (update :major inc)
               (assoc :minor 0 :patch 0))
    :minor (-> version-data
               (update :minor inc)
               (assoc :patch 0))
    :patch (update version-data :patch inc)))

(defn- validate
  [x f msg]
  (when (and (some? x)
             (not (f x)))
    (throw (IllegalArgumentException. msg))))

(defn- unparse-version
  [version-data]
  (let [{:keys [major minor patch snapshot?]} version-data]
    (str major
         "."
         minor
         "."
         patch
         (when snapshot?
           "-SNAPSHOT"))))

(defn advance-version
  "Advances the version number and (by default) updates VERSION.txt and deps.edn files.

  :kind - :major, :minor, or :patch, defaults to :patch
  :snapshot - true to add snapshot suffix, false to remove it, nil to leave it as-is
  :dry-run - print new version number, but don't update"
  [options]
  (let [{:keys [kind snapshot dry-run]} options
        _ (validate snapshot boolean? ":snapshot must be true or false")
        _ (validate kind #{:major :minor :patch} ":kind must be :major, :minor, or :patch")
        kind' (or kind :patch)
        version-data (parse-version version)
        version-data' (cond-> (advance version-data kind')
                        (some? snapshot) (assoc :snapshot? snapshot))
        new-version (unparse-version version-data')]
    (if dry-run
      (println "New version:" new-version)
      (update-version {:version new-version}))))
