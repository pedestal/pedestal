(ns {{namespace}}.rendering
  (:require [domina :as dom]
            [io.pedestal.app.render.push :as render]
            [io.pedestal.app.render.push.templates :as templates]
            [io.pedestal.app.render.push.handlers.automatic :as d])
  (:require-macros [{{namespace}}.html-templates :as html-templates]))

(def templates (html-templates/{{name}}-templates))

(defn render-page [renderer [_ path] transmitter]
  (let [parent (render/get-parent-id renderer path)
        id (render/new-id! renderer path)
        html (templates/add-template renderer path (:{{name}}-page templates))]
    (dom/append! (dom/by-id parent) (html {:id id :message ""}))))

(defn render-message [renderer [_ path _ new-value] transmitter]
  (templates/update-t renderer path {:message new-value}))

(defn render-config []
  [[:node-create  [:greeting] render-page]
   [:node-destroy   [:greeting] d/default-exit]
   [:value [:greeting] render-message]])
