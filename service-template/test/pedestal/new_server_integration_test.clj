; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

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

;; This code was heavily inspired by Overtone's version, thanks!
;; https://github.com/overtone/overtone/blob/e3de1f7ac59af7fa3cf75d696fbcfc2a15830594/src/overtone/helpers/file.clj#L360
(defn mk-tmp-dir!
  "Creates a unique temporary directory on the filesystem. Typically in /tmp on
  *NIX systems. Returns a File object pointing to the new directory. Raises an
  exception if the directory couldn't be created after 10000 tries."
  []
  (let [base-dir (io/file (System/getProperty "java.io.tmpdir"))
        base-name (str (java.util.UUID/randomUUID))
        tmp-base (str base-dir java.io.File/separator base-name)
        max-attempts 100]
    (loop [num-attempts 1]
      (if (= num-attempts max-attempts)
        (throw (Exception. (str "Failed to create temporary directory after " max-attempts " attempts.")))
        (let [tmp-dir-name (str tmp-base num-attempts)
              tmp-dir (io/file tmp-dir-name)]
          (if (.mkdir tmp-dir)
            tmp-dir
            (recur (inc num-attempts))))))))

(def tempdir (mk-tmp-dir!))

(defn- sh-exits-successfully
  [full-app-name & args]
  (let [sh-result (sh/with-sh-dir full-app-name (apply sh/sh args))]
    (println (:out sh-result))
    (is (zero? (:exit sh-result))
       (format "Expected `%s` to exit successfully" (string/join " " args)))))

(deftest ^:not-travis generated-app-has-correct-files
  (let [app-name "test-app"
        full-app-name (.getPath (io/file tempdir app-name))]
    (println (sh/with-sh-dir project-dir (sh/sh lein "install")))
    (println (sh/with-sh-dir tempdir (sh/sh lein "new" "pedestal-service" app-name)))
    (println "Created app at" full-app-name)
    (is (.exists (io/file full-app-name "project.clj")))
    (is (.exists (io/file full-app-name "README.md")))
    (is (.exists (io/file full-app-name "src" "test_app" "service.clj")))
    (sh-exits-successfully full-app-name lein "test")
    (sh-exits-successfully full-app-name lein "with-profile" "production" "compile" ":all")
    (sh/sh "rm" "-rf" full-app-name)))

(deftest ^:not-travis generated-app-with-namespace-has-correct-files
  (let [app-name "pedestal.test/test-ns-app"
        full-app-name (.getPath (io/file tempdir "test-ns-app"))]
   (println (sh/with-sh-dir project-dir (sh/sh lein "install")))
   (println (sh/with-sh-dir tempdir (sh/sh lein "new" "pedestal-service" app-name)))
   (println "Created app at" full-app-name)
   (is (.exists (io/file full-app-name "project.clj")))
   (is (.exists (io/file full-app-name "README.md")))
   (is (.exists (io/file full-app-name "src" "pedestal" "test" "test_ns_app" "service.clj")))
   (sh-exits-successfully full-app-name lein "test")
   (sh-exits-successfully full-app-name lein "with-profile" "production" "compile" ":all")
   (sh/sh "rm" "-rf" full-app-name)))
