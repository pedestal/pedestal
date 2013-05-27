; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns pedestal.new-server-integration-test
  (:require [clojure.test :refer :all]
            [clojure.java.shell :as sh]
            [clojure.string :as string]
            [clojure.java.io :as io]))

(def lein (or (System/getenv "LEIN_CMD") "lein"))

(def project-dir
  (->
   (ClassLoader/getSystemResource *file*)
   io/file .getParent io/file .getParent))

(let [file (java.io.File/createTempFile "filler" ".txt")]
  (def tempdir (doto (io/file (.getParent file) (str (java.util.UUID/randomUUID)))
                 .mkdirs)))

(defn- sh-exits-successfully
  [full-app-name & args]
  (let [sh-result (sh/with-sh-dir full-app-name (apply sh/sh args))]
    (println (:out sh-result))
    (is (zero? (:exit sh-result))
       (format "Expected `%s` to exit successfully" (string/join " " args)))))

(deftest generated-app-has-correct-files
  (let [app-name "test-app"
        full-app-name (.getPath (io/file tempdir app-name))]
    (println (:out (sh/with-sh-dir project-dir (sh/sh lein "install"))))
    (println (:out (sh/with-sh-dir tempdir (sh/sh lein "new" "pedestal-service" app-name))))
    (println "Created app at" full-app-name)
    (is (.exists (io/file full-app-name "project.clj")))
    (is (.exists (io/file full-app-name "README.md")))
    (is (.exists (io/file full-app-name "src" "test_app" "service.clj")))
    (sh-exits-successfully full-app-name lein "test")
    (sh-exits-successfully full-app-name lein "with-profile" "production" "compile" ":all")
    (sh/sh "rm" "-rf" full-app-name)))

(deftest generated-app-with-namespace-has-correct-files
  (let [app-name "pedestal.test/test-ns-app"
        full-app-name (.getPath (io/file tempdir "test-ns-app"))]
   (println (:out (sh/with-sh-dir project-dir (sh/sh lein "install"))))
   (println (:out (sh/with-sh-dir tempdir (sh/sh lein "new" "pedestal-service" app-name))))
   (println "Created app at" full-app-name)
   (is (.exists (io/file full-app-name "project.clj")))
   (is (.exists (io/file full-app-name "README.md")))
   (is (.exists (io/file full-app-name "src" "pedestal" "test" "test_ns_app" "service.clj")))
   (sh-exits-successfully full-app-name lein "test")
   (sh-exits-successfully full-app-name lein "with-profile" "production" "compile" ":all")
   (sh/sh "rm" "-rf" full-app-name)))
