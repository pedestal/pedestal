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
            [net.lewisship.build :refer [requiring-invoke deploy-jar]]
            [net.lewisship.trace :as trace :refer [trace]]
            [clojure.tools.build.api :as b]
            [net.lewisship.build.versions :as v]))

(trace/setup-default)

;; General notes: have to do a *lot* of fighting with executing particular build commands from the root
;; rather than in each module's sub-directory.

(def version-file "VERSION.txt")
(def group-name 'io.pedestal)
(def version (-> version-file slurp str/trim))

(def module-dirs
  ;; Keep these in dependency order
  ["log"
   "metrics"
   "interceptor"
   "route"
   "service"
   ;; And then the others:
   "aws"
   "jetty"
   "service-tools"])

;; Working around this problem (bug)?
;; Manifest type not detected when finding deps for io.pedestal/pedestal.log in coordinate #:local{:root "../log"}
;; Basically, not recognizing relative paths correctly; I think they are being evaluated at the top level, so ".." is the
;; directory above the pedestal workspace.
;; See https://clojure.atlassian.net/browse/TDEPS-106

(defn- classpath-for
  [dir overrides]
  (binding [b/*project-root* dir]
    (println "Reading" dir "...")
    (let [basis (b/create-basis {:override-deps overrides})
          roots (:classpath-roots basis)]
      (map (fn [path]
             (if (str/starts-with? path "/")
               path
               (str dir "/" path)))
           roots))))

(defn- canonical
  "Expands a relative path to a full path."
  [path]
  (-> path io/file .getAbsolutePath))

(defn- as-override
  [coll dir-name]
  (let [project-name (symbol (name group-name)
                             (str "pedestal." dir-name))
        project-dir (canonical dir-name)]
    (assoc coll project-name {:local/root project-dir})))

(defn- build-project-classpath
  []
  (let [overrides (reduce as-override {} module-dirs)]
    (mapcat #(classpath-for % overrides) module-dirs)))

(defn- build-full-classpath
  [& paths]
  (let [all-paths (-> (reduce into [] paths)
                      distinct
                      sort)
        [absolute-paths relative-paths] (split-with #(str/starts-with? % "/") all-paths)]
    (concat relative-paths absolute-paths)))

(defn codox
  "Generates combined Codox documentation for all sub-projects."
  [options]
  (let [{:keys [output-path]} options
        classpath (build-full-classpath
                    (build-project-classpath)
                    (:classpath-roots (b/create-basis {:aliases [:codox]})))
        codox-config (cond-> {:metadata {:doc/format :markdown}
                              :name (str (name group-name) " libraries")
                              :version version
                              :source-paths (mapv #(str % "/src") module-dirs)
                              :source-uri "https://github.com/pedestal/pedestal/blob/{version}/{filepath}#L{line}"}
                             output-path (assoc :output-path output-path))
        expression `(do
                      ((requiring-resolve 'codox.main/generate-docs) ~codox-config)
                      ;; Above returns the output directory name, "target/doc", which gets printed
                      ;; by clojure.main, so override that to nil on success here.
                      nil)
        ;;  The API version mistakenly requires :basis, so bypass it.
        process-params (requiring-invoke clojure.tools.build.tasks.process/java-command
                                         {:cp classpath
                                          :main "clojure.main"
                                          :main-args ["--eval" (pr-str expression)]})
        _ (println "Starting codox ...")
        {:keys [exit]} (b/process process-params)]
    (when-not (zero? exit)
      (println "Codox process exited with status:" exit)
      (System/exit exit))))


(defn- workspace-dirty?
  []
  (not (str/blank? (b/git-process {:git-args "status -s"}))))


(defn- ensure-workspace-clean
  []
  (when (workspace-dirty?)
    (println "Error: workspace contains changes, those must be committed first")
    (System/exit 1)))

(defn deploy-all
  "Builds and deploys all sub-modules.

  The workspace must be clean (no uncommitted changes).

  :dry-run - install to local Maven repository, but do not deploy to remote
  :force - deactivate the dirty workspace check
  :sign-key-id - key id to sign with, if not provided, defaults to environment variable CLOJARS_GPG_ID"
  [{:keys [dry-run sign-key-id force]}]
  (when-not force
    (ensure-workspace-clean))

  (println (str "Building version " version (when dry-run " (dry run)") " ..."))

  (let [build-and-install (requiring-resolve 'io.pedestal.deploy/build-and-install)
        ;; We only care about the Leiningen service-template when either deploying, or
        ;; when changing the version number.
        module-dirs' (conj module-dirs "service-template")
        artifacts-data (mapv #(build-and-install % version) module-dirs')]
    (when-not dry-run
      (println "Deploying ...")
      (run! #(deploy-jar (assoc % :sign-key-id sign-key-id)) artifacts-data)))
  (println "done"))

(defn update-version
  "Updates the version of the library.

  This changes the root VERSION.txt file and edits all deps.edn files to reflect the new version as well.

  :version (string, required) - new version number
  :commit (boolean, default false) - if true, then the workspace will be committed after changes; the workspace
  must also start clean
  :tag (boolean, default false) if true, then tag with the version number, after commit"
  [{:keys [version commit tag force]
    :or {commit false}}]
  (when (and commit
             (not force))
    (ensure-workspace-clean))

  ;; Ensure the version number is parsable
  (v/parse-version version)
  (doseq [dir module-dirs]
    (println "Updating" dir "...")
    (requiring-invoke io.pedestal.build/update-version-in-deps dir version))

  (println "Updating service-template (Leiningen template project) ...")

  (requiring-invoke io.pedestal.build/update-service-template version)

  (b/write-file {:path version-file
                 :string version})

  (println "Updated to version:" version)

  (when commit
    (b/git-process {:git-args ["commit" "-a" "-m" (str "Advance to version " version)]})
    (println "Committed version change")
    (when tag
      (b/git-process {:git-args ["tag" version]})
      (println "Tagged commit"))))

(defn- validate
  [x f msg]
  (when (and (some? x)
             (not (f x)))
    (throw (IllegalArgumentException. msg))))

(defn advance-version
  "Advances the version number and updates VERSION.txt and deps.edn files. By default,
  the file changes are committed and tagged.

  Version numbers are of the form <major>.<minor>.<version>(-<stability>(-<index>));
  the stability suffix is for non-release versions; it can be \"-SNAPSHOT\" or
  \"-beta-<index>\" or \"-rc-<index>\".

  :level - :major, :minor, :patch, :snapshot, :beta, :rc, :release
  :dry-run - print new version number, but don't update
  :commit - see update-version
  :tag - see update-version

  :major, :minor, :patch increment their numbers and discard the stability suffix.
  So \"1.3.4-rc-2\" with level :minor would advance to \"1.4.0\".

  :release strips off the stability suffix so \"1.3.4-rc-3\" with level :release
  would advance to \"1.3.4\".  It's not valid to use level :release with a release version (one with
  no stability suffix).

  :snapshot, :beta, or :rc: If the existing stability matches, then the index number at the end is incremented,
  otherwise the stability is set to the level and the index is set to 1 (except for :snapshot, which has no
  index).

  \"1.3.4\" with level :snapshot becomes \"1.3.4-SNAPSHOT\".
  \"1.3.4-SNAPSHOT\" with level :beta becomes \"1.3.4-beta-1\".
  \"1.3.4-beta-2\" with level :beta becomes \"1.3.4-beta-3\".

  Following a release, the pattern is `clj -T:build advance-version :level :patch` followed
  by `clj -T:build advance-version :level :snapshot :commit true`."
  [options]
  (let [{:keys [level dry-run]} options
        advance-levels #{:major :minor :patch :release :snapshot :beta :rc}
        _ (validate level advance-levels
                    (str ":level must be one of: "
                         (->> advance-levels (map name) (str/join ", "))))
        new-version (-> version v/parse-version (v/advance (or level :patch)) v/unparse-version)]
    (if dry-run
      (println "New version:" new-version)
      (update-version (-> options
                          (dissoc :level :dry-run)
                          (assoc :version new-version))))))


(defn cve-check
  [_]
  (let [cp (->> (build-full-classpath (build-project-classpath))
                (filter #(str/ends-with? % ".jar"))
                (str/join ":"))
        {:keys [exit]} (do
                         (println "Running CVE check")
                         (b/process {:command-args ["clojure"
                                                    "-T:nvd" ":classpath"
                                                    (str \" cp \")]}))]
    (System/exit exit)))
