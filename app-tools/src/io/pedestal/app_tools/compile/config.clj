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
