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
  "Seperated out to avoid unnecessary code loading."
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
  "Updates intra-module dependencies to use the provided version; this uses rewrite-edn to do so without losing
  formatting or comments."
  [module-dir version]
  (let [deps-path (str module-dir "/deps.edn")
        nodes (-> deps-path
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
    (b/write-file {:path deps-path
                   :string (str nodes')})))

(defn- fixup-version
  "Reads lines from path; each line is passed to f.  If f returns a non-nil result
  then that is a replacement of the original line, otherwise the original line is passed through unchanged."
  [path f]
  (let [lines (-> path
                  slurp
                  str/split-lines)
        lines' (map #(if-let [replacement (f %)]
                       replacement
                       %)
                    lines)]
    (b/write-file {:path path
                   ;; Ensure a blank line at the end.
                   :string (str/join "\n" (conj lines' ""))})))

(defn update-service-template
  "Clumsily updates the dependencies in a project.clj file in the given directory."
  [version]
  (fixup-version "service-template/project.clj"
                 (fn [line]
                   (when-let [[_ prefix suffix] (re-matches #"(?x)
                   (\s*
                    \(defproject \s+
                    .+?  # project name
                    \s+ \" # quote before version
                   )       # end of prefix
                   .+?
                   (\"     # start of suffix
                    .*)"
                                                            line)]
                     (str prefix version suffix))))
  (fixup-version "service-template/src/leiningen/new/pedestal_service/project.clj"
                 (fn [line]
                   (when-let [[_ prefix suffix] (re-matches #"(?x)

                   (.*?
                    io\.pedestal/.+?
                    \s+
                    \")
                   .+?
                   (\" .*)
                   "
                                                            line)]
                     (str prefix version suffix)))))
