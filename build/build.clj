(ns build
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [net.lewisship.build :refer [requiring-invoke]]
            [clojure.tools.build.api :as b]))

;; Testing source links
(def version "0.5.10" #_ "0.5.11-SNAPSHOT")

(defn echo
  [_]
  (println "Echo!"))


(def module-dirs
  (str/split "aws immutant interceptor jetty log route service service-tools tomcat" #" "))

;; Working around this problem (bug)?
;; Manifest type not detected when finding deps for io.pedestal/pedestal.log in coordinate #:local{:root "../log"}
;; Basically, not recognizing relative paths correctly (I think they are being evaluated at the top level, so ".." is the
;; directory about the pedestal workspace.
;;
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

;; TODO: This *might* be accomplished easier by adding :local/root deps to the :codox alias.
(defn codox
  [_]
  (let [overrides (reduce as-override {} module-dirs)
        project-classpath (mapcat #(classpath-for % overrides) module-dirs)
        codox-classpath (:classpath-roots (b/create-basis {:aliases [:codox]}))
        full-classpath (->> project-classpath
                            (concat codox-classpath)
                            distinct
                            sort)
        codox-config {:metadata {:doc/format :markdown}
                      :name "io.pedestal"
                      :version version
                      :source-paths (mapv #(str % "/src") module-dirs)
                      :source-uri "https://github.com/pedestal/pedestal/blob/{version}/{filepath}#L{line}"}
        expression `(do ((requiring-resolve 'codox.main/generate-docs) ~codox-config) nil)
        ;; The API version mistakenly requires :basis, so bypass it.
        process-params (requiring-invoke clojure.tools.build.tasks.process/java-command
                                         {:cp full-classpath
                                          :main "clojure.main"
                                          :main-args ["--eval" (pr-str expression)]})
        _ (println "Starting codox ...")
        {:keys [exit]} (b/process process-params)]
    (when-not (zero? exit)
      (println "Codox process exitted with status:" exit)
      (System/exit exit))))

#_(defn generate
    "Generates Codox documentation.

     The caller must pass :version (e.g., \"0.1.0\") and :project-name (e.g. 'io.github.hlship/build-tools).

     The :codox/config key in deps.edn provides defaults passed to codox; typically contains keys :description and :source-uri."
    [params]
    (let [{:keys [aliases codox-version codox-config version project-name exclusions]
           :or {codox-version "0.10.8"}} params
          _ (do
              (assert version "no :version specified")
              (assert project-name "no :project-name specified"))
          basis (b/create-basis {:extra {:deps {'codox/codox
                                                {:mvn/version codox-version
                                                 :exclusions exclusions}}}
                                 :aliases aliases})
          codox-config' (merge
                          {:metadata {:doc/format :markdown}}
                          (:codox/config basis)
                          codox-config
                          {:version version
                           :name (str project-name)})
          expression `(do ((requiring-resolve 'codox.main/generate-docs) ~codox-config') nil)
          process-params (b/java-command
                           {:basis basis
                            :main "clojure.main"
                            :main-args ["--eval" (pr-str expression)]})]
      (b/process process-params)
      nil))
