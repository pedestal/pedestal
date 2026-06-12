; Copyright 2024 Nubank NA
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
            [clojure.tools.build.api :as b]
            [babashka.fs :as fs]
            [clj-commons.ansi :refer [perr pout]]
            [net.lewisship.build.versions :as v])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))


;; General notes: have to do a *lot* of fighting with executing particular build commands from the root
;; rather than in each module's sub-directory.

(def version-file "VERSION.txt")
(def group-name 'io.pedestal)
(def version (-> version-file slurp str/trim))

(def module-dirs
  ;; Keep these in dependency order
  ["common"
   "telemetry"
   "log"
   "interceptor"
   "error"
   "route"
   "service"
   "servlet"
   ;; And then the others:
   "jetty"
   "http-kit"
   "embedded"])

;; Working around this problem (bug)?
;; Manifest type not detected when finding deps for io.pedestal/pedestal.log in coordinate #:local{:root "../log"}
;; Basically, not recognizing relative paths correctly; I think they are being evaluated at the top level, so ".." is the
;; directory above the pedestal workspace.
;; See https://clojure.atlassian.net/browse/TDEPS-106

(defn- classpath-for
  [dir overrides]
  (binding [b/*project-root* dir]
    (println "Reading" dir "...")
    (let [basis-opts {:override-deps overrides}
          basis      (b/create-basis basis-opts)
          roots      (:classpath-roots basis)]
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
        project-dir  (canonical dir-name)]
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
        classpath      (build-full-classpath
                         (build-project-classpath)
                         (:classpath-roots (b/create-basis {:aliases [:codox]})))
        codox-config   (cond-> {:metadata     {:doc/format :markdown}
                                :name         (str (name group-name) " libraries")
                                :version      version
                                :source-paths (mapv #(str % "/src") module-dirs)
                                :source-uri   "https://github.com/pedestal/pedestal/blob/{version}/{filepath}#L{line}"}
                         output-path (assoc :output-path output-path))
        expression     `(do
                          ((requiring-resolve 'codox.main/generate-docs) ~codox-config)
                          ;; Above returns the output directory name, "target/doc", which gets printed
                          ;; by clojure.main, so override that to nil on success here.
                          nil)
        ;;  The API version mistakenly requires :basis, so bypass it.
        process-params (requiring-invoke clojure.tools.build.tasks.process/java-command
                                         {:cp        classpath
                                          :main      "clojure.main"
                                          :main-args ["--eval" (pr-str expression)]})
        _              (println "Starting codox ...")
        {:keys [exit]} (b/process process-params)]
    (when-not (zero? exit)
      (println "Codox process exited with status:" exit)
      (System/exit exit))))


(defn lint
  "Runs clj-kondo on all sources across all modules."
  [options]
  (let [classpath    (conj (->> (build-project-classpath)
                                (remove #(str/ends-with? % ".jar"))
                                (remove #(str/ends-with? % "/classes")))
                           ;; Pedestal is an odd duck, all the tests are in a sub-module that
                           ;; (of course) is not published along with the other modules.
                           "tests/test")
        ;; Alternate idea is to locate all `deps.edn` files, and then find
        ;; all `src` and `test` below. That will help cover examples and guides as well
        ;; as modules.
        kondo-run!   (requiring-resolve 'clj-kondo.core/run!)
        kondo-print! (requiring-resolve 'clj-kondo.core/print!)
        results      (kondo-run! (merge {:lint   classpath
                                         :config [{:output  {:progress    false
                                                             :linter-name true}
                                                   :lint-as '{io.pedestal.http.route-test/defhandler    clojure.core/defn
                                                              io.pedestal.http.route-test/defon-request clojure.core/defn}
                                                   :linters
                                                   {:unresolved-symbol
                                                    {:exclude '[(clojure.test/is [match?])]}
                                                    :deprecated-var          {:level :off}
                                                    :deprecated-namespace    {:level :off}
                                                    :single-key-in           {:level :error}
                                                    :used-underscore-binding {:level :error}}}]}
                                        options))]
    (kondo-print! results)
    (when (pos? (get-in results [:summary :error] 0))
      (perr [:bold.red "Linter found errors."])
      (System/exit -1)))

  (pout [:bold.green "clj-kondo approves 😉"]))

(defn- workspace-dirty?
  []
  (not (str/blank? (b/git-process {:git-args "status -s"}))))

(defn- ensure-workspace-clean
  []
  (when (workspace-dirty?)
    (perr [:red [:bold "ERROR: "] "workspace contains changes, those must be committed first"])
    (System/exit 1)))

(defn deploy-all
  "Builds and deploys all sub-modules.

  The workspace must be clean (no uncommitted changes).

  Credentials are read from environment variables CLOJARS_USERNAME and CLOJARS_PASSWORD
  (a Clojars deploy token). Artifacts are deployed unsigned by default; this is normally
  executed by the Release workflow in CI.

  :dry-run - install to local Maven repository, but do not deploy to remote
  :force - deactivate the dirty workspace check
  :sign - if true, GPG-sign artifacts before deploying (default: false)
  :sign-key-id - key id to sign with (implies :sign); defaults to environment variable CLOJARS_GPG_ID"
  [{:keys [dry-run sign sign-key-id force]}]
  (when-not force
    (ensure-workspace-clean))

  (println (str "Building version " version (when dry-run " (dry run)") " ..."))

  (let [build-and-install (requiring-resolve 'io.pedestal.deploy/build-and-install)
        artifacts-data    (mapv #(build-and-install % version) module-dirs)
        sign?             (boolean (or sign sign-key-id))]
    (when-not dry-run
      (println "Deploying ...")
      (run! #(deploy-jar (assoc %
                                :sign-artifacts? sign?
                                :sign-key-id sign-key-id))
            artifacts-data)))
  (println "done"))

(defn install
  "Installs all libraries to local Maven repository."
  [_]
  (deploy-all {:force true :dry-run true}))

(defn update-version
  "Updates the version of the library.

  This changes the root VERSION.txt file and edits all deps.edn files to reflect the new version as well.

  :version (string, required) - new version number
  :commit (boolean, default false) - if true, then the workspace will be committed after changes; the workspace
  must also start clean
  :tag (boolean, default false) if true, then tag with the version number, after commit"
  [{:keys [version commit tag force]
    :or   {commit false}}]
  (when (and commit
             (not force))
    (ensure-workspace-clean))

  ;; Ensure the version number is parsable
  (v/parse-version version)
  (doseq [dir module-dirs]
    (requiring-invoke io.pedestal.build/update-version-in-deps (str dir "/deps.edn") version))

  (doseq [path (->> (fs/glob "docs" "**/deps.edn")
                    (map str))]
    (requiring-invoke io.pedestal.build/update-version-in-deps path version))

  (requiring-invoke io.pedestal.build/update-version-in-misc-files version)

  (b/write-file {:path   version-file
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

  :level - :major, :minor, :patch, :snapshot, :beta, :rc, :alpha, :release
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
        advance-levels #{:major :minor :patch :release :snapshot :beta :rc :alpha}
        _              (validate level advance-levels
                                 (str ":level must be one of: "
                                      (->> advance-levels (map name) (str/join ", "))))
        new-version    (-> version v/parse-version (v/advance (or level :patch)) v/unparse-version)]
    (if dry-run
      (println "New version:" new-version)
      (update-version (-> options
                          (dissoc :level :dry-run)
                          (assoc :version new-version))))))


(defn ^:private git!
  "Runs a git command, inheriting IO; exits on failure."
  [& args]
  (let [{:keys [exit]} (b/process {:command-args (into ["git"] args)})]
    (when-not (zero? exit)
      (perr [:red [:bold "ERROR: "] "git " (str/join " " args) " failed"])
      (System/exit exit))))

(def ^:private changelog-file "CHANGELOG.md")

(defn ^:private stamp-changelog-release-date
  "Replaces the version's UNRELEASED heading in CHANGELOG.md with today's date.
  Returns the new heading."
  [version]
  (let [unreleased-heading (str "## " version " - UNRELEASED")
        date               (.format (LocalDate/now)
                                    (DateTimeFormatter/ofPattern "d MMM yyyy" Locale/ENGLISH))
        released-heading   (str "## " version " - " date)
        content            (slurp changelog-file)]
    (when-not (str/includes? content unreleased-heading)
      (perr [:red [:bold "ERROR: "] changelog-file " does not contain heading: " unreleased-heading])
      (System/exit 1))
    (b/write-file {:path   changelog-file
                   :string (str/replace-first content unreleased-heading released-heading)})
    released-heading))

(defn ^:private add-unreleased-changelog-heading
  "Adds an UNRELEASED heading for the next version above the just-released heading."
  [next-base-version released-heading]
  (let [content (slurp changelog-file)]
    (b/write-file {:path   changelog-file
                   :string (str/replace-first content released-heading
                                              (str "## " next-base-version " - UNRELEASED\n\n"
                                                   released-heading))})))

(defn ^:private release-versions
  [current-version level]
  (let [new-version (-> current-version v/parse-version (v/advance level) v/unparse-version)
        final?      (not (str/includes? new-version "-"))]
    {:new-version  new-version
     :final?       final?
     :next-version (when final?
                     (-> new-version
                         v/parse-version
                         (v/advance :patch)
                         (v/advance :snapshot)
                         v/unparse-version))}))

(defn release
  "Cuts a release: advances the version, updates CHANGELOG.md, commits, tags, and pushes.
  The pushed tag triggers the Release workflow in CI, which deploys all modules to Clojars
  and creates a GitHub release.

  For final releases (e.g. \"1.2.3\"), the version's UNRELEASED heading in CHANGELOG.md is
  stamped with today's date and, after tagging, the version is advanced to the next patch
  SNAPSHOT (with a fresh UNRELEASED changelog heading) to reopen development.
  Beta/RC releases skip the changelog and snapshot steps.

  :level - :major, :minor, :patch, :release (the default), :snapshot, :beta, :rc, :alpha
  :dry-run - print the computed versions, but change nothing"
  [{:keys [level dry-run]
    :or   {level :release}}]
  (validate level #{:major :minor :patch :release :snapshot :beta :rc :alpha}
            ":level must be one of: major, minor, patch, release, snapshot, beta, rc, alpha")
  (if dry-run
    (let [{:keys [new-version next-version]} (release-versions version level)]
      (if next-version
        (pout "Would release version " [:bold new-version] ", then advance to " [:bold next-version])
        (pout "Would release version " [:bold new-version])))
    (do
      (ensure-workspace-clean)
      (git! "pull" "--ff-only")
      (let [current-version (-> version-file slurp str/trim)
            {:keys [new-version final? next-version]} (release-versions current-version level)]
        (pout "Releasing version " [:bold new-version] " ...")
        (let [released-heading (when final?
                                 (stamp-changelog-release-date new-version))]
          (update-version {:version new-version})
          (git! "commit" "-a" "-m" (str "Release " new-version))
          (git! "tag" new-version)
          (git! "push" "origin" "HEAD")
          (git! "push" "origin" new-version)
          (pout [:green "Pushed tag " [:bold new-version] "; CI will deploy to Clojars and create the GitHub release"])
          (when final?
            (add-unreleased-changelog-heading (str/replace next-version #"-SNAPSHOT$" "")
                                              released-heading)
            (update-version {:version next-version})
            (git! "commit" "-a" "-m" (str "Advance to version " next-version))
            (git! "push" "origin" "HEAD")
            (pout [:green "Advanced to " [:bold next-version]])))))))

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
