(ns io.pedestal.app-tools.dev
  (:require [io.pedestal.app-tools.server :as server]
            [io.pedestal.app-tools.build :as build]
            [io.pedestal.app-tools.compile.repl :as repl]
            [cljs.repl.browser :refer [repl-env]]
            [cemerick.piggieback :as pb]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn load-config []
  (let [config (or (some-> (clojure.java.io/resource "config.edn")
                            slurp
                            edn/read-string)
                    (throw (ex-info "no config.edn file found in resources paths. try adding one to <your-app>/config." {})))]
    (build/expand-config config)))

(defonce ^:private app-development-server nil)
(defonce ^:private watcher nil)
(def config (load-config))

(defn reload-config
   "Reload config.edn into local config var.

   You must (stop) and (start) your server for this to take effect."
  []
  (alter-var-root #'config (constantly (load-config))))

(defn cljs-repl
  "Start a ClojureScript REPL. If the application development server
  is running, go to the 'fresh' view to connect to this REPL. To use
  this REPL from any part of an application, start the repl client
  manually.

  The namespace 'io.pedestal.app.net.repl-client' contains a 'repl'
  function for starting the REPL client. Call this function from an
  application or call it from the JavaScript console

  io.pedestal.app.net.repl_client.repl();

  to start the client."
  []
  (pb/cljs-repl :repl-env (repl-env))
  (println "CLJS REPL launched. Visit (or refresh) http://localhost:3000/fresh.html"))

(defn init
  "Create a new app development server and ensure that required
  directories exist."
  [port config-name]
  (.mkdirs (io/file build/*tools-public*))
  (.mkdirs (io/file build/*public*))
  (assert (contains? config config-name)
          (str "Valid config names are " (pr-str (keys config)) "."))
  (alter-var-root #'app-development-server (constantly
                                            (server/app-development-server
                                             port (get config config-name)))))

(defn start
  "Initialize and start an application development web server. The
  server will serve one application at a time. The default port is
  3000. The default application is the first in config."
  ([]
   (start 3000 (ffirst config)))
  ([port]
   (start port (ffirst config)))
  ([port config-name]
   (init port config-name)
   ((:start-fn app-development-server))
   :ok))

(defn stop
  "Stop the current application development server."
  []
  ((:stop-fn app-development-server)))

(def ^{:doc "Compile JavaScript for this project. Pass an application name to compile
  all aspects of an application."}
  compile-cljs (repl/project-compiler config))

(def ^{:doc "Delete generated JavaScript. Pass an application name to clean a
  specific application. Application names must be keywrods."}
  clean-cljs
  (build/cleaner build/*public* config))

(defn watch
  "Incrementally build this project when files change. This will only
  build a single aspect at a time, where an aspect is something
  like :development or :production. Aspect names are configured in
  config/config.edn.

  If this project contains more than one application, the default is
  to build all of them. Pass a vector of config names as the first
  argument to watch a specific group of applications.

  Output of the build goes to out/public. This function is useful when
  serving out/public from another server (for example the service
  server) where on-demand build is not available."
  ([aspect]
     (watch (vec (keys config)) aspect))
  ([config-names aspect]
     (assert (vector? config-names) "config-names must be a vector")
     (let [watched-configs (select-keys config config-names)
           missing-aspect (reduce (fn [a [k v]]
                                    (if (contains? (:aspects v) aspect)
                                      a
                                      (conj a k)))
                                  []
                                  watched-configs)]
       (if (seq missing-aspect)
         (println (str "Error: the watched config " missing-aspect " do not contain a " aspect " aspect."))
         (do (println "watching" config-names "/" aspect)
             (let [w (build/watcher (vals watched-configs) aspect)]
               ((:start-fn w))
               (alter-var-root #'watcher (constantly w))
               :ok))))))

(defn unwatch
  "Stop the currently running watcher."
  []
  (if watcher
    (do ((:stop-fn watcher))
        (alter-var-root #'watcher (constantly nil))
        :ok)
    (println "Oops! No watcher is running.")))

(defn tools-help
  "Show basic help for each function in this namespace."
  []
  (println)
  (println "Start a new app development server with (start) or (start port config)")
  (println "Type (cljs-repl) to start a ClojureScript REPL")
  (println "----")
  (println "Type (reload-config) to reload your configuration from disk")
  (println "Type (start) or (start port config) to initialize and start a server")
  (println "Type (stop) to stop the current server")
  (println "----")
  (println "Type (watch aspect) to build a specific aspect when it changes")
  (println "Type (unwatch) to stop the current watcher")
  (println))

(defn -main
  "Used by `lein run` to start the server from the commandline."
  ([] (start))
  ([port] (start (Integer/parseInt port))))
