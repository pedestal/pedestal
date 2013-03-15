; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.app-tools.compile.config)

(defn- jstr
  "Use the :js location provided in opts to construct a path to a
  JavaScript file."
  [public opts & paths]
  (apply str public "/" (get-in opts [:application :generated-javascript]) "/" paths))

(defn cljs-compilation-options
  ([public config]
     (cljs-compilation-options public config :development))
  ([public config environment]
     (let [build-opts (assoc (:build config) :output-dir (jstr public config "out"))
           aspect (get-in config [:aspects environment])
           build-opts (assoc build-opts
                        :output-to (jstr public config (:out-file aspect)))]
       (merge (if-let [optimizations (:optimizations aspect)]
                (assoc build-opts :optimizations optimizations)
                build-opts)
              (:compiler-options aspect)))))
