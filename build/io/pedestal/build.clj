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
            [clojure.tools.build.api :as b]))

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
  [module-dir version]
  (let [deps-path (str module-dir "/deps.edn")
        nodes (-> deps-path
                  slurp
                  r/parse-string)
        ;; Since this is specific to io.pedestal, we don't have to worry as much about
        ;; other aliases or dependency types, just the main :deps.
        fix-deps (fn [node]
                   (reduce (fn [n k]
                             (r/assoc-in n [k :mvn/version] version))
                           node
                           (affected-keys node)))
        nodes' (r/update nodes :deps fix-deps)]
    (b/write-file {:path deps-path
                   :string (str nodes')})))
