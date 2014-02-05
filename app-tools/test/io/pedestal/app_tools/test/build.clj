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
            [io.pedestal.app.tree :as tree]
            [net.cgrand.enlive-html :as enlive]
            [clojure.java.io :as io])
  (:use io.pedestal.app-tools.build
        clojure.test))

(deftest test-split-path
  (let [split-path #'io.pedestal.app-tools.build/split-path
        path (str (clojure.java.io/file "some" "path"))]
    (is (= (split-path (str path)) ["some" "path"]))))

(deftest test-expand-app-config
  (testing "expand-watch-files"
    ;; Given the following in test/:
    ;; test/glob-test
    ;; └── foo
    ;;    ├── bar
    ;;    │   ├── baz.clj
    ;;    │   └── baz.cljs
    ;;    └── bar.clj
    (testing "front and back anchored single file selection"
      (let [expanded-config (expand-app-config {:build {:watch-files {:macro ["^test/glob-test/foo/bar\\.clj$"]}}})
            watch-files (-> expanded-config :build :watch-files)]
        (is watch-files)
        (is (= 1 (count watch-files)))
        (is (= :macro (-> watch-files first :tag)))
        (is (re-find #"bar\.clj$"
                     (-> watch-files first :source)))))

    (testing "multi-file selection"
      (let [expanded-config (expand-app-config {:build {:watch-files {:macro ["ba.\\.clj$"]}}})
            watch-files (-> expanded-config :build :watch-files)]
        (is watch-files)
        (is (= 2 (count watch-files)))
        (is (every? #(re-find #"ba(r|z)\.clj$" (:source %)) watch-files)))))

  (testing "intern-trigger-patterns"
    (let [expanded-config (expand-app-config {:build {:triggers {:html ["foo/bar\\.clj$"]}}})
            html-triggers (-> expanded-config :build :triggers :html)]
      (is (= 1 (count html-triggers)))
      (is (= (str #"foo/bar\.clj$")
             (str (first html-triggers))))))

  (testing "no expansions"
      (is (= {:build {:watch-files nil
                      :triggers nil}}
             (expand-app-config {})))
      (is (= {:a {:build {:watch-files nil
                          :triggers nil}}}
             (expand-config {:a {}})))))


(deftest test-design-templates
  (let [config {:application {:output-root :public}} 
        aspect nil]
    (build! config aspect)
    (let [template (enlive/html-resource (io/file "out/tools/public/design/test-template.html"))
          container (enlive/html-resource (io/file "out/tools/public/design/test-container.html"))
          sub-template (enlive/html-resource (io/file "out/tools/public/design/test-sub-template.html"))
          template-selector [:body :div.container :div#test-template :p]
          sub-template-selector [:body :div.container :div#content :div#test-sub-template]          
          container-selector [:body :div.container :h3]]
	    (testing "_within works fine for design templates"
              (is (= 1 (count (enlive/select container container-selector))))
              (is (= 1 (count (enlive/select template container-selector))))
              (is (= 1 (count (enlive/select template template-selector))))
              (is (= 1 (count (enlive/select sub-template container-selector))))
              (is (= 1 (count (enlive/select sub-template sub-template-selector)))))
     (testing "Container should not have any trace of template"
                       (is (= 0 (count (enlive/select container template-selector)))))
     (testing "Can we nest templates in multiple levels?"
                       (is (= 1 (count (enlive/select sub-template template-selector))))))))


(deftest test-application-templates
  (let [config {:application {:output-root :public
                              :generated-javascript "generated-js"
                              :default-template "test-application.html"}
                :aspects {:development {:uri "/test-template-dev.html"
                                        :main 'test-template.start}}
                :build {:ignore [".*"]}; can't easily test cljs compilation
                } 
        aspect :development]
    (build! config aspect)
    (let [app-template (enlive/html-resource (io/file "out/public/test-template-dev.html"))
          design-template (enlive/html-resource (io/file "out/tools/public/design/test-template.html"))
          expected [:body :div.container]
          actual [:body :div.application ]]
      (testing "application templates are rendered correctly" 
               (is (= 1 (count (enlive/select app-template actual)))))
      (testing "application templates should match design templates" 
               (is (= 1 (count (enlive/select app-template expected))))))))


(deftest test-aspect-based-templates
  (let [config {:application {:output-root :public
                              :generated-javascript "generated-js"
                              :default-template "test-application.html"}
                :aspects {:development {:uri "/dev-layout-app.html"
                                        :main 'test-app.start
                                        :template "dev-layout.html"}}
                :build {:ignore [".*"]}; can't easily test cljs compilation
                } 
        aspect :development]
    (build! config aspect)
    (let [app-template (enlive/html-resource (io/file "out/public/dev-layout-app.html"))
          design-template (enlive/html-resource (io/file "out/tools/public/design/test-template.html"))
          expected [:body :div.container]
          actual [:body :div.dev-layout ]]
      (testing "application templates follow aspect-based options" 
               (is (= 1 (count (enlive/select app-template actual)))))
      (testing "aspect-based templates don't match design templates" 
               (is (= 1 (count (enlive/select app-template expected))))))))

