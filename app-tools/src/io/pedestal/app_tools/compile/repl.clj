;; Copyright (c) 2012 Relevance, Inc. All rights reserved.

(ns io.pedestal.app-tools.compile.repl
  (:use [io.pedestal.app-tools.compile :only [thread-safe-compile!]]
        [io.pedestal.app-tools.compile.config :only [cljs-compilation-options]]
        [io.pedestal.app-tools.build :only [*public*]]))

(defn compile-project
  ([configurations config-name environment]
     (println (str "compiling " config-name "/" environment))
     @(thread-safe-compile! (cljs-compilation-options *public* (get configurations config-name) environment)))
  ([configurations config-name]
     (doseq [env (keep (fn [[k v]] (when (:out-file v) k)) (:aspects (get configurations config-name)))]
       (compile-project configurations config-name env)))
  ([configurations]
     (doseq [config-name (keys configurations)]
       (compile-project configurations config-name))))

(defn project-compiler [configurations]
  (partial compile-project configurations))
