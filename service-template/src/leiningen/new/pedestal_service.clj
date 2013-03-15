; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

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
