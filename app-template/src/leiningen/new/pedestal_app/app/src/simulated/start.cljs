(ns {{namespace}}.simulated.start
  (:require [io.pedestal.app.render.push.handlers.automatic :as d]
            [{{namespace}}.start :as start]
            ;; This needs to be included somewhere in order for the
            ;; tools to work.
            [io.pedestal.app-tools.tooling :as tooling]))

(defn ^:export main []
  {{#annotated?}}
  ;; Create an application which uses the data renderer. The :data-ui
  ;; aspect is configured to run this main function. See
  ;;
  ;; config/config.edn
  ;;
  {{/annotated?}}
  (start/create-app d/data-renderer-config))
