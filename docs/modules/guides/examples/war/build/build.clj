(ns build
  (:require [babashka.fs :as fs]
            clojure.pprint
            [clojure.tools.build.api :as b]))

(defn- copy [from work-dir sub-dir]
  (let [out-dir (fs/path work-dir sub-dir)]
    (println "Copying" from "to" sub-dir)
    (fs/copy-tree from out-dir)))

(defn war [_]
  (let [work-dir    (fs/path "target/archive")
        lib-dir     (fs/path work-dir "WEB-INF/lib")
        classes-dir (fs/path work-dir "WEB-INF/classes")
        basis       (b/create-basis)]
    (fs/delete-tree work-dir)
    (fs/create-dirs lib-dir)
    (fs/create-dirs classes-dir)
    (doseq [file (-> basis :classpath keys sort)]
      (let [path (fs/path file)]
        (println "Copying" (fs/file-name path))
        (if (fs/directory? path)
          (fs/copy-tree path classes-dir)
          (fs/copy path lib-dir))))
    (println "Copying web.xml")
    (fs/copy "web.xml" (fs/path work-dir "WEB-INF"))
    (fs/zip (fs/path "target/app.war") [work-dir] {:root "target/archive"})
    (println "Created target/app.war")))
