; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:no-doc io.pedestal.http.resources.impl
  "Implementation details for io.pedestal.http.resource; subject to change at any time."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [ring.util.io :as util.io])
  (:import (jakarta.servlet.http HttpServletResponse)
           (java.io File InputStream)
           (java.net URL)
           (java.nio.channels Channels FileChannel)
           (java.nio.file OpenOption StandardOpenOption)
           (java.util Date)
           (java.util.jar JarFile)))

;; This adapts some of the ideas from ring.util.response

(def ^:private open-options
  (into-array OpenOption [StandardOpenOption/READ]))

(defn- should-stream?
  [request stream-size]
  (let [{:keys [servlet-response]} request
        buffer-size-bytes (if servlet-response
                            (.getBufferSize ^HttpServletResponse servlet-response)
                            ;; Assume 1500 MTU
                            1460)]
    (<= buffer-size-bytes stream-size)))

(defn- make-streamable-file-body
  [file]
  (fn [request]
    (if (should-stream? request (.length file))
      ;; This is mutable and should not be cached:
      (FileChannel/open (.toPath file) open-options)
      ;; If async is not worthwhile, then just return the file itself
      ;; to stream its contents.
      file)))

(defn- make-streamable-jar-entry-body
  [jar-file jar-entry]
  (fn [request]
    (let [is ^InputStream (.getInputStream jar-file jar-entry)]
      (if (should-stream? request (.length jar-entry))
        (Channels/newChannel is)
        is))))

(defmulti resource-data
          "Returns resource data for a file: or jar: URL."
          (fn [^URL url]
            (-> url .getProtocol keyword)))

(defmethod resource-data :file
  [url]
  (when-let [file (io/as-file url)]
    {:last-modified   (util.io/last-modified-date file)
     :content-length  (.length file)
     :response-body   (fn [_] file)
     :streamable-body (make-streamable-file-body file)}))

(defmethod resource-data :jar
  [url]
  (let [[_ file-path entry-path] (re-matches
                                   #"(?x)
    file:
    (.+)
    !/
    (.+)"
                                   (.getPath url))
        ;; This opens the jar file, there's nothing to close it.
        ;; TODO: Caching of jar files
        jar-file      (-> file-path io/file JarFile.)
        jar-entry     (.getJarEntry jar-file entry-path)
        last-modified (-> jar-entry .getLastModifiedTime .toMillis Date.)]
    {:last-modified   last-modified
     :content-length  (.getSize jar-entry)                  ; uncompressed size
     :response-body   (fn [_] (.getInputStream jar-file jar-entry))
     :streamable-body (make-streamable-jar-entry-body jar-file jar-entry)}))

(defn- traversal?
  [path]
  (string/includes? path ".."))

(defn- find-file-named
  [^File dir ^String filename]
  (let [path (File. dir filename)]
    (when (.isFile path)
      path)))

(defn- find-file-starting-with
  [^File dir ^String prefix]
  (first
    (filter
      #(string/starts-with? prefix (-> ^File % .getName .toLowerCase))
      (.listFiles dir))))

(defn- find-index-file
  [^File dir]
  (or (find-file-named dir "index.html")
      (find-file-named dir "index.htm")
      (find-file-starting-with dir "index.")))

(defn url-for-file
  [root-file path index-files?]
  (when-not (traversal? path)
    (let [file  (if (= "" path)
                  root-file
                  (io/file root-file path))
          file' (cond
                  (.isDirectory file)
                  (when index-files?
                    (find-index-file file))

                  (.exists file)
                  file)]
      (when file'
        (io/as-url file')))))

