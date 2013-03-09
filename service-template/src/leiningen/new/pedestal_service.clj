(ns leiningen.new.pedestal-service
  (:require [leiningen.new.templates :refer [renderer name-to-path ->files]]))

(defn pedestal-service
  "A pedestal service project template."
  [name & args]
  (let [render (renderer "pedestal-service")
        data {:name name
              :sanitized (name-to-path name)}]
    (println (str "Generating a pedestal-service application called " name "."))
    (->files data
             ["README.md" (render "README.md" data)]
             ["project.clj" (render "project.clj" data)]
             [".gitignore" (render ".gitignore" data)]
             ["src/{{sanitized}}/server.clj" (render "server.clj" data)]
             ["src/{{sanitized}}/service.clj" (render "service.clj" data)]
             ["test/{{sanitized}}/service_test.clj" (render "service_test.clj" data)]
             ["config/logback.xml" (render "logback.xml" data)]
             ["dev/user.clj" (render "user.clj" data)]
             ["dev/dev.clj" (render "dev.clj" data)])))
