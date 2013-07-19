; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app-tools.test.build
  (:require [io.pedestal.app.protocols :as p]
            [io.pedestal.app.messages :as msg]
            [io.pedestal.app.tree :as tree])
  (:use io.pedestal.app-tools.build
        clojure.test))

(deftest test-split-path
  (let [split-path #'io.pedestal.app-tools.build/split-path
        path (str (clojure.java.io/file "some" "path"))]
    (is (= (split-path (str path)) ["some" "path"]))))

(deftest test-expand-config
  ;; Given the following in test/:
  ;; test/glob-test
  ;; └── foo
  ;;    ├── bar
  ;;    │   ├── baz.clj
  ;;    │   └── baz.cljs
  ;;    └── bar.clj
  (testing "front and back anchored single file selection"
    (let [expanded-config (expand-config {:build {:watch-files {:macro [#"^test/glob-test/foo/bar\.clj$"]}}})
          watch-files (-> expanded-config :build :watch-files)]
      (is watch-files)
      (is (= 1 (count watch-files)))
      (is (= :macro (-> watch-files first :tag)))
      (is (re-find #"bar\.clj$"
                   (-> watch-files first :source)))))

  (testing "multi-file selection"
    (let [expanded-config (expand-config {:build {:watch-files {:macro [#"ba.\.clj$"]}}})
          watch-files (-> expanded-config :build :watch-files)]
      (is watch-files)
      (is (= 2 (count watch-files)))
      (is (every? #(re-find #"ba(r|z)\.clj$" (:source %)) watch-files)))))
