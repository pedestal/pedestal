; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app-tools.build
  (:use [io.pedestal.app-tools.compile.config :only [cljs-compilation-options]]
        [io.pedestal.app-tools.host-page :only [application-host]]
        [io.pedestal.app.templates :only [load-html]])
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [io.pedestal.app-tools.compile :as compile]
            [io.pedestal.app.util.scheduler :as scheduler]
            [io.pedestal.app.protocols :as p]
            [io.pedestal.app.util.log :as log]
            [io.pedestal.service.interceptor :as interceptor :refer [definterceptorfn]])
  (:import [java.io File]))

(def ^:dynamic *tools-public* "out/tools/public")
(def ^:dynamic *public* "out/public")

(defn relative-without-leading-slash
  "Get the relative path of a File without its leading slash."
  [file]
  (clojure.string/replace (.getPath file) #"^\./" ""))

(defn re-some
  "Return first match of regular expression in `res` with string `s`. Returns
  nil if none found."
  [res s]
  (some #(re-find % s) res))

(defn- files-matching
  "Return files (inside the project) that match any of `res`.
  
  Patterns in `res` should match relative to the root of the project without a leading `./`
  
  Expect to match `app/templates/something.html`
  NOT `./app/templates/something.html` or
  `/path/to/repo/app/templates/something.html`"
  [res]
  (let [files (file-seq (io/file "./"))
        relative-filename (fn [f] )]
    (filter (fn [f] (->> f
                         relative-without-leading-slash
                         (re-some res)))
            files)))

(defn- tag-and-patterns->sources
  "For a pattern `re` and `tag`, create a list of config maps (`{:source <abs-path> :tag tag>}`)"
  [tag res]
  (let [files (files-matching res)]
    (->> files
         (map #(.getAbsolutePath %))
         (map #(hash-map :source % :tag tag)))))

(defn- expand-watch-files
  "Expand a tag-patterns map into a complete list of source-tag maps."
  [watch-files]
  (if (seq watch-files)
    (vec (mapcat (fn [[tag patterns]] (tag-and-patterns->sources tag patterns))
                 watch-files))))

(defn expand-config
  "Expand all short-hands in `config`.

  Expansions:

  - [:build :watch-files] will expand a map of tags to file patterns into a
    vector of \"source\" maps. Each \"source\" map has keys `:tag`, the original
    key tag in `:watch-files` map, and `:source`, a file relative to the project
    that matched a provided pattern.

    Patterns match relative to the root of your project, without a leading `./`.

    For example:

        {:build {:watch-files {:html [#\"^app/templates/.*\\.html$\"}}

    becomes:

        [{:tag :html :source \"/path/to/app/templates/webpage.html\"}, ...]"
  [config]
  (-> config
      (update-in [:build :watch-files] expand-watch-files)))

(defn expand-configs
  "Expand each config in config-map"
  [configs]
  (apply hash-map (mapcat (fn [[name config]] [name (expand-config config)])
                          configs)))

(defn- split-path [s]
  (string/split s (re-pattern (java.util.regex.Pattern/quote File/separator))))

(defn- ensure-ends-with-sep [p]
  (if (.endsWith p File/separator) p (str p File/separator)))

(defn- get-public [k]
  (when k
    (ensure-ends-with-sep
      (case k
        :public *public*
        :tools-public *tools-public*))))

(defn- ensure-directory [dir]
  (let [path (remove empty? (split-path dir))]
    (loop [dir (io/file (first path))
           children (next path)]
      (when (not (.exists dir)) (.mkdir dir))
      (when children
        (recur (io/file dir (first children)) (next children))))))

(defn- filter-files [files]
  (->> files
         (remove #(.isDirectory %))
         (remove #(.startsWith (.getName %) "."))))

(defn- files-to-process [& dirs]
  (for [d dirs
        file (filter-files (file-seq (io/file d)))]
    {:path (split-path (.getPath file))
     :modified (.lastModified file)}))

(defn- make-path [public & parts]
  (str (get-public public) (string/join File/separator (vec parts))))

(defmulti analyze-file (fn [{p :path}] (vec (take 2 p))))

(defmulti when-modified (fn [t] (:transform t)))

(defmethod when-modified :compass-compile [t]
  (let [f (io/file *tools-public* ".compass-compile-modified")
        output-modified (.lastModified f)
        result (when (or (not (.exists f))
                         (> (:modified t) output-modified))
                 t)]
    (when result
      (do (.mkdirs (.getParentFile f))
          (spit f "delete this file to force reload of scss files")))
    result))

(defmethod when-modified :default [t]
  (let [f (io/file (:output-to t))
        output-modified (.lastModified f)]
    (when (or (not (.exists f))
              (> (:modified t) output-modified))
      t)))

(defn- file-ext [path]
  (let [file-name (last path)
        i (.lastIndexOf file-name ".")]
    (.toLowerCase (subs file-name i))))

(defn- get-asset-transform [m p]
  (when-modified
   (case (file-ext p)
     ".scss" {:path p :modified m :transform :compass-compile}
     {:path p
      :modified m
      :output-to (apply make-path :public (drop 3 p))
      :transform :identity})))

(defmethod analyze-file ["tools" "public"] [{p :path m :modified}]
  (when-modified
   {:path p
    :modified m
    :output-to (apply make-path :tools-public (drop 2 p))
    :transform (if (.endsWith (last p) ".html") :template :identity)}))

(defmethod analyze-file ["app" "assets"] [{p :path m :modified}]
  (get-asset-transform m p))

(defmethod analyze-file ["app" "templates"] [{p :path m :modified}]
  (when-modified
   {:path p
    :modified m
    :output-to (apply make-path :tools-public "design" (drop 2 p))
    :transform :template}))

(defmethod analyze-file :default [{p :path}]
  nil)

(defmulti transform-files (fn [transform fs] transform))

(defmethod transform-files :identity [_ fs]
  (mapv (fn [f]
          (log/info :transform :identity :path (string/join File/separator (:path f)))
          (assoc f
            :from (string/join File/separator (:path f))
            :transform :copy))
        fs))

(defmethod transform-files :copy [_ fs]
  (doseq [f fs]
    (log/info :transform :copy :from (:from f) :to (:output-to f))
    (let [to (io/file (:output-to f))]
      (.mkdirs (.getParentFile to))
      (io/copy (io/file (:from f)) to))))

(defmethod transform-files :write [_ fs]
  (doseq [f fs]
    (log/info :transform :write :path (string/join File/separator (:path f)))
    (let [file (:output-to f)]
      (.mkdirs (.getParentFile (io/file file)))
      (spit file (:content f)))))

(defmethod transform-files :template [_ fs]
  (mapv (fn [f]
          (log/info :transform :template :path (string/join File/separator (:path f)))
          (assoc f
            :content (load-html (io/file (string/join File/separator (:path f))))
            :transform :write))
        fs))

(defmethod transform-files :compass-compile [_ fs]
  (try
    (let [pb (ProcessBuilder. ["compass" "compile"])
          pb (.directory pb (io/file "."))
          p (.start pb)]
      (.waitFor p)
      (doseq [line (string/split-lines (slurp (.getInputStream p)))]
        (log/info :compass-output line)))
    (catch Throwable e
      (log/error :msg "Error trying to run 'compass compile'. scss files will be ignored!"
                 :exception e))))

(defmethod transform-files :default [_ _]
  nil)

(defn- process-files-internal [fs]
  (let [remaining (remove nil? (mapcat (fn [[k v]] (transform-files k v)) fs))]
    (when (not (empty? remaining))
      (recur (group-by :transform remaining)))))

(defn- process-files [& dirs]
  (let [fs (remove nil? (map analyze-file (apply files-to-process dirs)))]
    (process-files-internal (group-by :transform fs))))

(defn- drop-leading-sep [s]
  (if (.startsWith s File/separator)
    (subs s 1)
    s))

(defn- make-template [config output-root aspect]
  (let [dir (get-public (get-in config [:aspects aspect :output-root]))]
    (when dir (ensure-directory dir))
    (spit (str (or dir output-root) (drop-leading-sep
                                     (get-in config [:aspects aspect :uri])))
          (application-host config aspect))))

(defn compile-worker! [config aspect name whitelist]
  (let [whitelist (conj whitelist #"io/pedestal/app.*")
        sources (compile/all-cljs-on-classpath)
        filtered-sources (filter #(some (fn [x] (re-matches x (:js-file-name %))) whitelist)
                                 sources)
        options (-> (cljs-compilation-options *public*
                                              (update-in config [:aspects aspect]
                                                         assoc :out-file (str name ".js")
                                                         :optimizations :advanced)
                                              aspect)
                    (dissoc :watch-files :ignore :triggers))]
    (when (compile/compilation-required? filtered-sources (:output-to options))
      (doseq [s filtered-sources]
        (log/info :worker-name name :include-file (:js-file-name s)))
      (compile/build-sources! filtered-sources options))))

(defn build!
  "Builds the current project into the out directory."
  [config aspect]
  (let [output-root (get-public (get-in config [:application :output-root]))]
    (ensure-directory output-root)
    (when aspect
      (compile/compile! (cljs-compilation-options *public* config aspect))
      (when-let [workers (get-in config [:aspects aspect :workers])]
        (doseq [[name whitelist] workers]
          (compile-worker! config aspect name whitelist)))
      (make-template config output-root aspect))
    (process-files "tools" "app")))

(def build-agent (agent nil))

(defn thread-safe-build! [config aspect]
  (let [p (promise)]
    (send build-agent (fn [_] (deliver p (try (build! config aspect)
                                             (catch Throwable e
                                               (do (log/error :exception e)
                                                   {:error (.getMessage e)}))))))
    p))

(def ^:private scheduler (scheduler/scheduler))

(defn- start-watcher [state configs aspect]
  (assoc state
    :task
    (scheduler/periodic scheduler 500
                        (fn [] (doseq [b (map #(thread-safe-build! % aspect) configs)]
                                (deref b))))))

(defn- stop-watcher [state]
  (scheduler/cancel (:task state)))

(defn watcher [configs aspect]
  (let [watcher-state (atom {})]
    {:state watcher-state
     :start-fn #(swap! watcher-state start-watcher configs aspect)
     :stop-fn #(stop-watcher @watcher-state)}))

(defn- aspect-from-request [config request]
  (first (keep (fn [[k v]] (when (= (:uri v) (:uri request)) k))
               (:aspects config))))

(defn- attempt-build? [request]
  (let [uri (:uri request)]
    (or (= uri "/")
        (= uri "/_tools/render")
        (= uri "/_tools/render/recording")
        (.endsWith (:uri request) ".html"))))

(defn- build-aspects [config request]
  (vector (if (= (:uri request) "/_tools/render/recording")
            (if-let [js-file (get-in config [:built-in :render :js-file])]
              (first (keep (fn [[k v]] (when (= (:out-file v) js-file) k))
                           (:aspects config)))
              :development)
            (aspect-from-request config request))))

(definterceptorfn builder
  "Interceptor that blocks further processing until all required build
  steps have been completed."
  [config]
  (interceptor/on-request
   (fn [request]
     (when (attempt-build? request)
       (let [t (System/currentTimeMillis)]
         (doseq [aspect (build-aspects config request)]
           (log/info :msg (str "LOGGING " aspect))
           @(thread-safe-build! config aspect))
         (log/info :msg "Build finished" t (- (System/currentTimeMillis) t) :units :ms)))
     request)))

(defn delete
  "Delete one or more files or directories. Directories are recursively
  deleted."
  [& paths]
  (doseq [path paths
          file (reverse (file-seq (io/file path)))]
    (.delete file)))

(defn clean-project
  ([dir configurations]
     (doseq [config-name (keys configurations)]
       (clean-project dir configurations config-name)))
  ([dir configurations config-name]
     (let [js (get-in (get configurations config-name) [:application :generated-javascript])
           f (io/file (str dir "/" js))]
       (when (.exists f)
         (log/info :message (str "deleting file: " (.getAbsolutePath f)))
         (delete (.getAbsolutePath f))))))

(defn cleaner [dir configurations]
  (partial clean-project dir configurations))
