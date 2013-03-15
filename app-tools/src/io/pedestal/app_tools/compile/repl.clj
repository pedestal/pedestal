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
