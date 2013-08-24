(ns {{namespace}}.simulated.start
  (:require [io.pedestal.app.render.push.handlers.automatic :as d]
            [{{namespace}}.start :as start]
            [{{namespace}}.rendering :as rendering]
            [goog.Uri]
            ;; This needs to be included somewhere in order for the
            ;; tools to work.
            [io.pedestal.app-tools.tooling :as tooling]))

(defn param [name]
  (let [uri (goog.Uri. (.toString  (.-location js/document)))]
    (.getParameterValue uri name)))

(defn ^:export main []
  {{#annotated?}}
  ;; Create an application which uses the data renderer. The :data-ui
  ;; aspect is configured to run this main function. See
  ;;
  ;; config/config.clj
  ;;
  {{/annotated?}}
  (start/create-app (if (= "auto" (param "renderer"))
                      d/data-renderer-config
                      (rendering/render-config))))
