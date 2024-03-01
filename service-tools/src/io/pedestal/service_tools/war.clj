; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.service-tools.war
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io])
  (:import (java.io ByteArrayInputStream InputStream)
           (java.util.jar Manifest
                          JarEntry
                          JarOutputStream)))

;; web.xml construction
;; --------------------

(defn app-server-ns [opts]
  (or (:server-ns opts)                                     ;; We need to do `or` to prevent a premature exit
      (do
        (println "ERROR: You need to specify a Pedestal :server-ns in your options"
                 (str "Your current Pedestal settings: " opts)
                 "Quitting...")
        (System/exit 1))))

(defn make-web-xml
  "Given a map of options,
  return a string of XML - the web.xml for the service/WAR.

  Available options and default values
   :web-xml - a slurpable path, which is returned as the web.xml string.
              NOTE: All other options will be ignored.
   :servlet-description \"Pedestal HTTP Servlet\"
   :servlet-display-name - defaults to :servlet-description
   :servlet-name \"PedestalServlet\"
   :servlet-class \"io.pedestal.servlet.ClojureVarServlet\"
   :url-patterns \"/*\"
   :server-ns - Requires there to be fns [servlet-init servlet-service servlet-destroy]"
  [opts]
  (if-let [web-xml (:web-xml opts)]
    (slurp web-xml)
    (let [server-ns (app-server-ns opts)
          {:keys [servlet-description
                  servlet-name
                  servlet-class
                  url-pattern]
           :or   {servlet-description "Pedestal HTTP Servlet"
                  servlet-name        "PedestalServlet"
                  servlet-class       "io.pedestal.servlet.ClojureVarServlet"
                  url-pattern         "/*"}} opts]
      (xml/indent-str
        (xml/sexp-as-element
          [:web-app {:xmlns              "http://java.sun.com/xml/ns/javaee"
                     :xmlns:xsi          "http://www.w3.org/2001/XMLSchema-instance"
                     :xsi:schemaLocation "http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd"
                     :version            "3.0"
                     :metadata-complete  "true"}
           [:description servlet-description]
           [:display-name (:servlet-display-name opts servlet-description)]
           [:servlet
            [:servlet-name servlet-name]
            [:servlet-class servlet-class]
            [:init-param
             [:param-name "init"]
             [:param-value (str server-ns "/servlet-init")]]
            [:init-param
             [:param-name "service"]
             [:param-value (str server-ns "/servlet-service")]]
            [:init-param
             [:param-name "destroy"]
             [:param-value (str server-ns "/servlet-destroy")]]]
           [:servlet-mapping
            [:servlet-name servlet-name]
            [:url-pattern url-pattern]]])))))

;; Manifest construction
;; ---------------------

(def default-pedestal-manifest
  {"Created-By" "Pedestal Service War Tooling"
   "Built-By"   (System/getProperty "user.name")
   "Build-Jdk"  (System/getProperty "java.version")})

(defn manifest-str
  "Given a map of manifest keys/values,
  Return a string of the single Manifest contents"
  [manifest-map]
  (reduce
    (fn [accumulated-manifest [k v]]
      (str accumulated-manifest "\n" k ": " v))
    "Manifest-Version: 1.0"
    (merge default-pedestal-manifest manifest-map)))

;; This is taken from Ring, it probably should be clojure.java.io
(defn string-input-stream
  "Returns a ByteArrayInputStream for the given String."
  ^InputStream [^String s]
  (ByteArrayInputStream. (.getBytes s)))

(defn make-manifest
  ([]
   (make-manifest {}))
  ([manifest-map]
   (Manifest. (string-input-stream (manifest-str manifest-map)))))

;; War construction
;; -----------------


(defn war-file-path [target-dir war-name]
  (.mkdirs (io/file target-dir))
  (str target-dir "/" war-name))

(defn skip-file? [file war-path exclusions]
  (or (re-find #"^\.?#" (.getName file))
      (re-find #"~$" (.getName file))
      (some #(re-find % war-path) exclusions)))

(defn in-war-path [war-path root file]
  (str war-path
       (-> (.toURI (io/file root))
           (.relativize (.toURI file))
           (.getPath))))

(defn write-entry [war ^String war-path entry]
  (.putNextEntry war (JarEntry. war-path))
  (io/copy entry war))

(defn file-entry [war opts war-path file]
  (when (and (.exists file)
             (.isFile file)
             (not (skip-file? file war-path (:war-exclusions opts [#"(^|/)\."]))))
    (write-entry war war-path file)))

(defn dir-entry [war opts war-root dir-path]
  (doseq [file (file-seq (io/file dir-path))]
    (let [war-path (in-war-path war-root dir-path file)]
      (file-entry war opts war-path file))))

;; A given `postprocess-fn` must take two args, the opts map and the war/war-stream

;;(concat [(:source-path opts)] (:source-paths opts)
;;                                   [(:resources-path opts)] (:resource-paths opts)
;;                                   [(:war-resources-path opts "war-resources")] (:war-resource-paths opts)

(defn write-war [opts war-path & postprocess-fns]
  (with-open [war-stream (-> (io/output-stream war-path)
                             (JarOutputStream. (make-manifest (:manifest opts))))]
    (doto war-stream
      (write-entry "WEB-INF/web.xml" (string-input-stream (make-web-xml opts)))
      (and (:compile-path opts)
           (dir-entry war-stream opts "WEB-INF/classes/" (:compile-path opts))))
    (doseq [path (distinct (:resource-paths opts))
            :when path]
      (dir-entry war-stream opts "WEB-INF/classes/" path))
    (doseq [pp-fn postprocess-fns]
      (pp-fn opts war-stream))
    war-stream))

(defn war
  "Create a PedestalService.war file.
  Optionally pass in a war file name.
  Various options are supported via the opt map
   :target-path - where the war will be saved; defaults to \".\"
   :manifest - a map of override/additional Manifest entries; keys and values are both strings
   :compile-path - an optional, additional path of compiled resources to include
   :resource-paths - a vector of all additional sources, resources, war-resources to include
   :war-exclusions - a vector of regex strings; patterns of file names to exclude in the war"
  ([opts]
   (war opts "PedestalService.war"))
  ([opts war-name-str]
   (let [war-path (war-file-path (:taget-path opts ".") war-name-str)]
     (app-server-ns opts)
     (write-war opts war-path)
     (println "Created" war-path)
     war-path)))
