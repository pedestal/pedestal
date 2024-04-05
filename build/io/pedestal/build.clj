; Copyright 2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.build
  "Separated out to avoid unnecessary code loading."
  (:require [borkdude.rewrite-edn :as r]
            [clojure.tools.build.api :as b]
            [clojure.string :as str]))

(defn- affected-keys
  [map-node]
  (->> map-node
       r/keys
       (keep (fn [k]
                 (let [k' (r/sexpr k)]
                   (when (and (symbol? k')
                              (-> k' namespace (= "io.pedestal")))
                     k'))))))

(defn update-version-in-deps
  "Updates dependencies to use the provided version; this uses rewrite-edn to do so without losing
  formatting or comments."
  [deps-path version]
  (let [nodes (-> deps-path
                  slurp
                  r/parse-string)
        ;; Since this is specific to io.pedestal, we don't have to worry about
        ;; other aliases or dependency types (:extra-deps, :override-deps, etc.), just the main :deps.
        fix-deps (fn [node]
                   (reduce (fn [n k]
                             (r/assoc-in n [k :mvn/version] version))
                           node
                           (affected-keys node)))
        nodes' (r/update nodes :deps fix-deps)]
    (println "Updating" deps-path)
    (b/write-file {:path deps-path
                   :string (str nodes')})))

(defn- fixup-version
  "Reads lines from path; each line is passed to f.  If f returns a non-nil result
  then that is a replacement of the original line, otherwise the original line is passed through unchanged."
  [path f]
  (let [lines (-> path
                  slurp
                  str/split-lines)
        lines' (mapv #(if-let [replacement (f %)]
                       replacement
                       %)
                    lines)]
    (println "Updating" path)
    (b/write-file {:path path
                   ;; Ensure a blank line at the end.
                   :string (str (str/join "\n" lines') \newline)})))

(defn update-version-in-misc-files
  [version]
  (update-version-in-deps "embedded/resources/io/pedestal/embedded/build/deps.tmpl" version)
  (fixup-version "docs/antora.yml"
                 (fn [line]
                   (when (str/includes? line "libs_version")
                     (str "    libs_version: " version)))))


