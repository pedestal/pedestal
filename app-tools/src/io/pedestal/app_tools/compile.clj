; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app-tools.compile
  (:use [cljs.closure :only [build -compile dependency-order Compilable]])
  (:require [clojure.java.io :as io]
            [clojure.java.classpath :as classpath]
            [clojure.tools.namespace.find :as ns-find]
            [clojure.tools.namespace.file :as ns-file]
            [io.pedestal.app.util.log :as log])
  (:import java.net.URL
           java.io.File))

(def ^:dynamic *shared-metadata* :shared)

(defn rename-to-js
  "Rename any Clojure-based file to a JavaScript file."
  [file-str]
  (clojure.string/replace file-str #".clj\w*$" ".js"))

(defn relative-path
  "Given a directory and a file, return the relative path to the file
  from within this directory."
  [dir file]
  (.substring (.getAbsolutePath file)
              (inc (.length (.getAbsolutePath dir)))))

(defn js-file-name
  "Given a directory and file, return the relative path to the
  JavaScript file."
  [dir file]
  (rename-to-js (relative-path dir file)))

(defn cljs-file?
  "Is the given file a ClojureScript file?"
  [f]
  (and (.isFile f)
       (.endsWith (.getName f) ".cljs")))

(defn project-cljs-files
  "Return all ClojureScript files in directories on the classpath."
  []
  (for [dir (classpath/classpath-directories) file (file-seq dir)
        :when (cljs-file? file)]
    {:js-file-name (js-file-name dir file)
     :tag :cljs
     :compile? true
     :source file}))

(defn ns-marked-as-shared?
  "Is the namespace of the given file marked as shared?"
  ([jar file-name]
     (when-let [ns-decl (ns-find/read-ns-decl-from-jarfile-entry jar file-name)]
       (*shared-metadata* (meta (second ns-decl)))))
  ([file-name]
     (when-let [ns-decl (ns-file/read-file-ns-decl file-name)]
       (*shared-metadata* (meta (second ns-decl))))))

(defn shared-files-in-jars
  "Return all Clojure files in jars on the classpath which are marked
  as being shared."
  []
  (for [jar (classpath/classpath-jarfiles)
        file-name (ns-find/clojure-sources-in-jar jar)
        :when (ns-marked-as-shared? jar file-name)]
    {:js-file-name (rename-to-js file-name)
     :tag :cljs-shared-lib
     :compile? true
     :source (java.net.URL. (str "jar:file:" (.getName jar) "!/" file-name))}))

(defn shared-files-in-dirs
  "Return all Clojure files in directories on the classpath which are
  marked as being shared."
  []
  (for [dir (classpath/classpath-directories)
        file (ns-find/find-clojure-sources-in-dir dir)
        :when (ns-marked-as-shared? file)]
    {:js-file-name (js-file-name dir file)
     :tag :cljs-shared
     :compile? true
     :source file}))

(defn all-cljs-on-classpath
  "Return all files on the classpath which can be compiled to
  ClojureScript."
  []
  (concat (shared-files-in-jars)
          (shared-files-in-dirs)
          (project-cljs-files)))

(defn delete-js-file [options js-file-name]
  (let [js-file (io/file (str (:output-dir options)
                              "/"
                              js-file-name))]
    (when (.exists js-file)
      (.delete js-file))))

(defn force-compilation [options sources]
  (let [{:keys [tags triggers output-dir]} options]
    (when (and tags triggers)
      (let [res (reduce (fn [r t] (into r (get triggers t))) [] tags)
            files (filter (fn [x] (some (fn [re] (re-matches re (:js-file-name x))) res))
                          (filter #(= (:tag %) :cljs) sources))]
        (doseq [f files]
          (log/info :task :forcing :js-path (:js-file-name f))
          (delete-js-file options (:js-file-name f)))))))

(defn build-sources!
  "Given a sequence of sources and compiler options, compile the
  sources to JavaScript. Each source is a map which contains the keys
  :js-file-name and :source. :js-file-name is the relative path to the
  compiled JavaScript file and :source is a File, File URL or Jar URL.
  :source may also be any single unit of compilation which implements
  Compilable."
  [sources options]
  (build (reify Compilable
           (-compile [_ options]
             (force-compilation options sources)
             (dependency-order
              (flatten (map (fn [{:keys [js-file-name source]}]
                              (log/info :task :compiling :js-path js-file-name)
                              (when (= (type source) java.io.File)
                                (.mkdirs source))
                              (-compile source (assoc options :output-file js-file-name)))
                            (filter :compile? sources))))))
         options))

(defn compilation-required?
  "Return truthy if any file in sources has a more recent modification
  time than the output file.

  The actual return value is a set :tags which have been modified of
  nil if nothing has been changed."
  [sources output-file]
  (let [out-f (io/file output-file)
        out-mod (.lastModified out-f)
        mod-times (for [src sources
                        :when (= (type (:source src)) java.io.File)]
                    {:modified (.lastModified (:source src)) :tag (:tag src)})
        modified (filter (fn [x] (> (:modified x) out-mod)) mod-times)]
    (when (not (empty? modified)) (set (distinct (map :tag modified))))))

(defn- non-cljs-files [files]
  (map (fn [f] {:source f}) files))

(defn tagged-files-in-dir [dir tag ext]
  (map (fn [f] {:source f :tag tag})
       (filter #(.endsWith % ext)
               (map #(.getAbsolutePath %) (file-seq (io/file dir))))))

(defn html-files-in
  "Return a sequence of file maps for all HTML files in the given
  directory."
  ([dir]
     (html-files-in dir :html))
  ([dir tag]
     (tagged-files-in-dir dir tag ".html")))

(defn clj-files-in
  "Return a sequence of file maps for all Clojure files in the given
  directory."
  [dir tag]
  (tagged-files-in-dir dir tag ".clj"))

(defn watched-sources
  "Return source files where the :js-file-name does not match a
  regular expression in :ignore."
  [options sources]
  (if-let [ignore (:ignore options)]
    (remove (fn [src] (some #(and (:js-file-name src)
                                 (re-matches % (:js-file-name src))) ignore)) sources)
    sources))

(defn- replace-strings-with-files [watched-files]
  (map (fn [{:keys [source] :as m}]
         (assoc m :source (if (string? source) (io/file source) source)))
       watched-files))

(defn compile!
  "Build all cljs in a project only when a build is necessary. Include a :watch-files
  key in options to add additional sources to watch.

  :watch-files can be used to add sources which are not on the
  classpath or to add sources which are not ClojureScript but are only
  watched for changes."
  [options]
  (let [{:keys [watch-files]} options
        sources (concat (all-cljs-on-classpath)
                        (replace-strings-with-files watch-files))]
    (let [sources (watched-sources options sources)]
      (when-let [tags (compilation-required? sources (:output-to options))]
        (do (build-sources! (filter :compile? sources) (assoc options :tags tags))
            true)))))

(def ^:private build-agent (agent nil))

(defn thread-safe-compile!
  "Build all cljs in a project using an agent to serialize builds.

  Returns a promise which can be blocked on to wait for compilation to
  complete."
  [options]
  (let [p (promise)]
    (send build-agent (fn [_] (deliver p (try (compile! options)
                                             (catch Throwable e {:error (.getMessage e)})))))
    p))

(comment

  ;; Example build options
  
  {;; Standard cljs compiler options
   :output-dir "example-build/out"
   :output-to "example-build/application.js"
   :optimizations :advanced
   ;; Add files to watch which are not ClojureScript files. Use this
   ;; to trigger compilation when an external resource
   ;; changes. External resources my include HTML files which are used
   ;; as templates or Clojure source files containing macros which are
   ;; used from ClojureScript.
   :watch-files (concat (html-files-in "templates")
                        (clj-files-in "src/cljs-macros" :macros))
   ;; JavaScript file prefixes to ignore during this build. Use this
   ;; to limit what is included in an application.
   :ignore [#"com/company/orders.*"]
   ;; Each file that is tracked has a type tag associated with it. The
   ;; default tag is :cljs. When we add files to watch, we can assign
   ;; a different tag. Triggers allow us to use regular expressions to
   ;; identify files which should be recompiled when a file with a
   ;; specific tag changes. In this example, we compile a file when
   ;; either an HTML file is changed or a macro source file is changed.
   :triggers {:html [#"com/company/fulfillment/renderers/common.js"]
              :macros [#"com/company/fulfillment/renderers/common.js"]}}

)
