(ns pedestal.new-server-integration-test
  (:require [clojure.test :refer :all]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io]))

(def project-dir
  (->
   (ClassLoader/getSystemResource *file*)
   io/file .getParent io/file .getParent))

(let [file (java.io.File/createTempFile "filler" ".txt")]
  (def tempdir (.getParent file)))

(def app-name "test-app")
(def full-app-name (.getPath (io/file tempdir app-name)))

(deftest generated-app-has-correct-files
  (println (:out (sh/with-sh-dir project-dir (sh/sh "lein" "install"))))
  (println (:out (sh/with-sh-dir tempdir (sh/sh "lein" "new" "pedestal-service" app-name))))
  (println "Created app at" full-app-name)
  (is (.exists (io/file full-app-name "project.clj")))
  (is (.exists (io/file full-app-name "README.md")))
  (is (.exists (io/file full-app-name "src" "test_app" "service.clj")))
  (println (:out (sh/with-sh-dir full-app-name (sh/sh "lein" "test"))))
  (sh/sh "rm" "-rf" full-app-name))
