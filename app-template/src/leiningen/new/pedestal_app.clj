(ns leiningen.new.pedestal-app
  (:use [leiningen.new.templates :only [renderer name-to-path ->files]]))

(defn base-files [render data]
  [[".gitignore" (render ".gitignore" data)]
   ["README.md" (render "README.md" data)]
   ["project.clj" (render "project.clj" data)]

   [(str "app/assets/stylesheets/{{name}}.css")
    (render "app/assets/stylesheets/project.css" data)]
   ["app/assets/javascripts/xpath.js"
    (render "app/assets/javascripts/xpath.js" data)]

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

   ["config/config.clj" (render "config/config.clj" data)]
   ["config/logback.xml" (render "config/logback.xml" data)]
   ["dev/user.clj" (render "dev/user.clj" data)]
   ["dev/dev.clj" (render "dev/dev.clj" data)]

   ["test/{{sanitized}}/test/behavior.clj" (render "test/behavior.clj" data)]])

(defn annotated-project [data]
  (let [render (renderer "pedestal-app/annotated")]
    (apply ->files data
           ["app/src/{{sanitized}}/behavior.clj" (render "app/src/behavior.clj" data)]
           ["app/src/{{sanitized}}/services.cljs" (render "app/src/services.cljs" data)]
           ["app/src/{{sanitized}}/html_templates.clj" (render "app/src/html_templates.clj" data)]
           ["app/src/{{sanitized}}/rendering.cljs" (render "app/src/rendering.cljs" data)]
           ["app/src/{{sanitized}}/start.cljs" (render "app/src/start.cljs" data)]
           ["app/src/{{sanitized}}/simulated/services.cljs" (render "app/src/simulated/services.cljs" data)]
           ["app/src/{{sanitized}}/simulated/start.cljs" (render "app/src/simulated/start.cljs" data)]
           ["app/templates/application.html" (render "app/templates/application.html" data)]
           ["app/templates/{{name}}.html" (render "app/templates/project.html" data)]
           (base-files render data))))

(defn default-project [data]
  (let [render (renderer "pedestal-app/plain")]
    (apply ->files data
           ["app/src/{{sanitized}}/behavior.clj" (render "app/src/behavior.clj" data)]
           ["app/src/{{sanitized}}/html_templates.clj" (render "app/src/html_templates.clj" data)]
           ["app/src/{{sanitized}}/rendering.cljs" (render "app/src/rendering.cljs" data)]
           ["app/src/{{sanitized}}/start.cljs" (render "app/src/start.cljs" data)]
           ["app/src/{{sanitized}}/simulated/start.cljs" (render "app/src/simulated/start.cljs" data)]
           ["app/templates/application.html" (render "app/templates/application.html" data)]
           ["app/templates/{{name}}.html" (render "app/templates/project.html" data)]
           (base-files render data))))

(defn pedestal-app
  "A Pedestal application project template."
  [name & args]
  (let [data {:name name
              :sanitized (name-to-path name)}]
    (case (first args)
      "annotated" (annotated-project data)
      (default-project data))))
