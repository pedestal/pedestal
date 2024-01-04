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

(ns io.pedestal.deploy
  (:require [clojure.tools.build.api :as b]))

;; Largely borrowed from https://github.com/hlship/build-tools/blob/main/src/net/lewisship/build/jar.clj

(defn build-and-install
  [dir version]
  (println dir "...")
  (binding [b/*project-root* dir]
    (let [basis (b/create-basis)
          project-name (or (get-in basis [:io.pedestal/build :project-name])
                           (symbol "io.pedestal" (str "pedestal." dir)))
          class-dir "target/classes"
          output-file (format "target/pedestal.%s-%s.jar" dir version)]
      (b/delete {:path "target"})
      (when-let [command (get-in basis [:io.pedestal/build :compile-command])]
        (let [{:keys [exit]} (b/process {:command-args command})]
          (when-not (zero? exit)
            (println "Compilation failed with status:" exit)
            (System/exit exit))))
      (b/write-pom {:class-dir class-dir
                    :lib project-name
                    :version version
                    :basis basis
                    ;; pedestal the GitHub organization, then pedestal the multi-module project, then the sub-dir
                    :scm       {:url (str "https://github.com/pedestal/pedestal/tree/master/" dir)}
                    :pom-data  [[:licenses
                                 [:license
                                  [:name "Eclipse Public License"]
                                  [:url "http://www.eclipse.org/legal/epl-v10.html"]]]]})
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
