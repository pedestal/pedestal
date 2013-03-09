(ns io.pedestal.app_tools.tooling
  (:require [io.pedestal.app-tools.rendering-view.client :as render-client]
            [io.pedestal.app-tools.rendering-view.record :as record]
            [io.pedestal.app.util.observers :as observers]
            [io.pedestal.app.util.console-log :as console-log]
            [io.pedestal.app.net.repl-client :as repl-client]))

(defn ^:export add-recording [app]
  (when (:app-model app)
    (record/init-recording (:app-model app)))
  app)

(defn ^:export add-logging [app]
  (when app
    (observers/subscribe :log console-log/log-map))
  app)
