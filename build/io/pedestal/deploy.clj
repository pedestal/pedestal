; Copyright 2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.deploy
  (:require [deps-deploy.deps-deploy :as d]
            [deps-deploy.gpg :as gpg]
            [net.lewisship.trace :refer [trace trace>]]
            [clojure.tools.build.api :as b]
            [cemerick.pomegranate.aether :as aether]))

(System/setProperty "aether.checksums.forSignature" "true")

(defn build-and-install
  [dir version]
  (println dir "...")
  (binding [b/*project-root* dir]
    (let [basis (b/create-basis)
          project-name (symbol "io.pedestal" (str "pedestal." dir))
          class-dir "target/classes"
          output-file (format "target/pedestal.%s-%s.jar" dir version)]
      (b/delete {:path "target"})
      (when (= "service" dir)
        ;; service is the only module that has Java compilation.
        ;; This could be converted to a flag stored in the deps.edn, to keep it clean.
        (let [{:keys [exit]} (b/process {:command-args ["clojure" "-T:build" "compile-java"]})]
          (when-not (zero? exit)
            (println "Compilation failed with status:" exit)
            (System/exit exit))))
      (b/write-pom {:class-dir class-dir
                    :lib project-name
                    :version version
                    :basis basis
                    ;; pedestal the GitHub organization, then pedestal the multi-module project, then the sub-dir
                    :scm {:url (str "https://github.com/pedestal/pedestal/" dir)}})
      (b/copy-dir {:src-dirs ["src" "resources"]
                   :target-dir class-dir})
      (b/jar {:class-dir class-dir
              :jar-file output-file})
      ;; Install it locally, so later modules can find it. This ensures that the
      ;; intra-project dependencies are correct in the generated POM files.
      (b/install {:basis basis
                  :lib project-name
                  :version version
                  :jar-file output-file
                  :class-dir class-dir})
      {:artifact-id project-name
       :version version
       :jar-path (str dir "/" output-file)
       :pom-path (b/pom-path {:lib project-name
                              :class-dir class-dir})})))

(defn- sign-path
  [sign-key-id path]
  (let [args ["--yes"
              "--armour"
              "--default-key" sign-key-id
              "--detach-sign"
              path]
        {:keys [success? exit-code out err]} (trace> (gpg/gpg {:args args})
                                                     :result %)]
    (when-not success?
      (binding [*out* *err*]
        (println (format "Error %d executing GPG" exit-code))
        (println out)
        (println err))
      (throw (ex-info "GPG Failure"
                      {:exit-code exit-code
                       :path path
                       :sign-key-id sign-key-id})))
    ;; Return the name of the created file
    (str path ".asc")))

(defn deploy-artifact
  [artifact-data sign-key-id]
  (let [{:keys [artifact-id version jar-path pom-path]} artifact-data
        _ (println (format "Deploying %s %s ..." artifact-id version))
        versioned-pom-path (str "target/" (name artifact-id) "-" version ".pom")
        _ (b/copy-file {:src pom-path
                        :target versioned-pom-path})
        paths [jar-path versioned-pom-path]
        upload-paths (into paths
                           (map #(sign-path sign-key-id %) paths))
        upload-artifacts (d/artifacts version upload-paths)
        aether-coordinates [(symbol artifact-id) version]]
    (trace :upload-artifacts upload-artifacts
           :coords aether-coordinates)
    (aether/deploy :artifact-map upload-artifacts
                   ;; Clojars is the default repository for uploads
                   :repository d/default-repo-settings
                   :transfer-listener :stdout
                   :coordinates aether-coordinates)))
