; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns leiningen.new.pedestal-app
  (:use [leiningen.new.templates :only [renderer name-to-path ->files
                                        project-name sanitize-ns]]))

(defn base-files [render data]
  [[".gitignore" (render ".gitignore" data)]
   ["README.md" (render "README.md" data)]
   ["project.clj" (render "project.clj" data)]

   [(str "app/assets/stylesheets/{{name}}.css")
    (render "app/assets/stylesheets/project.css" data)]
   [(str "app/assets/stylesheets/bootstrap.css")
    (render "tools/public/stylesheets/bootstrap.css" data)]
   [(str "app/assets/stylesheets/pedestal.css")
    (render "tools/public/stylesheets/pedestal.css" data)]
   
   ["app/assets/javascripts/xpath.js"
    (render "app/assets/javascripts/xpath.js" data)]

   ["app/src/{{sanitized}}/behavior.clj" (render "app/src/behavior.clj" data)]
   ["app/src/{{sanitized}}/html_templates.clj" (render "app/src/html_templates.clj" data)]
   ["app/src/{{sanitized}}/rendering.cljs" (render "app/src/rendering.cljs" data)]
   ["app/src/{{sanitized}}/start.cljs" (render "app/src/start.cljs" data)]
   ["app/src/{{sanitized}}/simulated/start.cljs" (render "app/src/simulated/start.cljs" data)]

   ["app/templates/application.html" (render "app/templates/application.html" data)]
   ["app/templates/tooling.html" (render "app/templates/tooling.html" data)]
   ["app/templates/{{name}}.html" (render "app/templates/project.html" data)]

   ["tools/public/404.html" (render "tools/public/404.html" data)]
   ["tools/public/design.html" (render "tools/public/design.html" data)]
   ["tools/public/favicon.ico" (render "tools/public/favicon.ico" data)]
   ["tools/public/index.html" (render "tools/public/index.html" data)]
   ["tools/public/stylesheets/bootstrap.css"
    (render "tools/public/stylesheets/bootstrap.css" data)]
   ["tools/public/stylesheets/pedestal.css"
    (render "tools/public/stylesheets/pedestal.css" data)]
   ["tools/public/javascripts/bootstrap.js"
    (render "tools/public/javascripts/bootstrap.js" data)]
   ["tools/public/javascripts/jquery-min.js"
    (render "tools/public/javascripts/jquery-min.js" data)]
   ["tools/public/javascripts/pedestal/js/api.js"
    (render "tools/public/javascripts/pedestal/js/api.js" data)]

   ["config/config.edn" (render "config/config.edn" data)]
   ["config/logback.xml" (render "config/logback.xml" data)]
   ["dev/user.clj" (render "dev/user.clj" data)]

   ["test/{{sanitized}}/test/behavior.clj" (render "test/behavior.clj" data)]])

(defn annotated-project [render data]
  (let [render (renderer "pedestal-app")]
    (apply ->files data
           ["app/src/{{sanitized}}/services.cljs" (render "app/src/services.cljs" data)]
           ["app/src/{{sanitized}}/simulated/services.cljs" (render "app/src/simulated/services.cljs" data)]
           (base-files render data))))

(defn unannotated-project [render data]
  (let [render (renderer "pedestal-app")]
    (apply ->files data
           (base-files render data))))

(defn pedestal-app
  "A Pedestal application project template."
  [name & args]
  (let [main-ns (sanitize-ns name)
        {:keys [annotated?] :as data} {:raw-name name
                                       :name (project-name name)
                                       :namespace main-ns
                                       :sanitized (name-to-path main-ns)
                                       :annotated? (not= "no-comment" (first args))}
        render (renderer "pedestal-app")]
    (if annotated?
      (annotated-project render data)
      (unannotated-project render data))))
