; Copyright 2026 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.deprecations-test
  "Every removed! call in Pedestal's sources (the errors for removed options that would otherwise be
  silently ignored) names the replacement and links to a section of the deprecations page that exists."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io File)))

;; Tests run from the tests directory, next to the modules.
(def ^:private modules
  ["common" "telemetry" "log" "interceptor" "error" "route" "service" "servlet" "jetty" "http-kit" "embedded"])

(def ^:private deprecations-page "../docs/modules/ROOT/pages/deprecations.adoc")

(defn- form-at
  "Returns the text of the form that starts with the open parenthesis at index i of s,
  skipping over strings, comments, and character literals."
  [^String s i]
  (loop [j     i
         depth 0]
    (let [c (.charAt s j)]
      (case c
        \" (recur (loop [k (inc j)]
                    (case (.charAt s k)
                      \\ (recur (+ k 2))
                      \" (inc k)
                      (recur (inc k))))
                  depth)
        \; (recur (or (string/index-of s "\n" j) (count s)) depth)
        \\ (recur (+ j 2) depth)
        (\( \[ \{) (recur (inc j) (inc depth))
        (\) \] \}) (if (= 1 depth)
                     (subs s i (inc j))
                     (recur (inc j) (dec depth)))
        (recur (inc j) depth)))))

(defn ^:private calls
  "Finds the forms in the modules' sources that start with a match of re (which ends at the symbol)."
  [re]
  (for [module modules
        ^File file (file-seq (io/file ".." module "src"))
        :when (string/ends-with? (.getName file) ".clj")
        :let [source  (slurp file)
              matcher (re-matcher re source)]
        start (take-while some? (repeatedly #(when (.find matcher) (.start matcher))))]
    {:file (str module "/" (.getPath (.relativize (.toURI (io/file ".." module)) (.toURI file))))
     :line (inc (count (filter #{\newline} (subs source 0 start))))
     :form (form-at source start)}))

(defn ^:private removal-sites
  "Calls to removed!."
  []
  (calls #"\((?:i/|internal/)?removed!\s"))

(defn ^:private anchors
  []
  (->> (slurp deprecations-page)
       (re-seq #"(?m)^\[#([a-z0-9-]+)\]")
       (map second)
       set))

(defn ^:private literal-docs-anchor
  [form]
  (second (re-find #":docs\s+\"([^\"]+)\"" form)))

(deftest every-removal-names-the-replacement-and-its-section
  (let [sites   (removal-sites)
        anchors (anchors)]
    ;; The :insecure-ssl? and :websockets options of Jetty's create-server, and the :array-coerce-fn
    ;; option of custom-json-parser.
    (is (= #{["jetty/src/io/pedestal/http/jetty.clj" 2]
             ["service/src/io/pedestal/http/body_params.clj" 1]}
           (->> sites (map :file) frequencies set))
        "found the removal sites")
    (doseq [{:keys [file line form]} sites]
      (testing (str file ":" line)
        (is (string/includes? form ":replacement"))
        (let [anchor (literal-docs-anchor form)]
          (is (some? anchor) "a literal :docs anchor")
          (is (contains? anchors anchor)
              (str "no section [#" anchor "] in " deprecations-page)))))))
