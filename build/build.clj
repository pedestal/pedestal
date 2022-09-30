(ns build
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [net.lewisship.build :refer [requiring-invoke]]
            [clojure.tools.build.api :as b]))

(def project-name 'io.pedestal)

;; While testing source links:
(def version "0.5.10" #_ "0.5.11-SNAPSHOT")

(def module-dirs
  (str/split "aws immutant interceptor jetty log route service service-tools tomcat" #" "))

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
