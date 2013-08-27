(ns {{namespace}}.rendering
  (:require [domina :as dom]
            [io.pedestal.app.render.push :as render]
            [io.pedestal.app.render.push.templates :as templates]
            [io.pedestal.app.render.push.handlers.automatic :as d])
  (:require-macros [{{namespace}}.html-templates :as html-templates]))

{{#annotated?}}
;; Load templates.

{{/annotated?}}
(def templates (html-templates/{{name}}-templates))

{{#annotated?}}
;; The way rendering is handled below is the result of using the
;; renderer provided in `io.pedestal.app.render`. The only requirement
;; for a renderer is that it must implement the Renderer protocol.
;;
;; This renderer dispatches to rendering functions based on the
;; requested change. See the render-config table below. Each render
;; function takes three arguments: renderer, render operation and a
;; a transmitter which is used to send data back to the application's
;; behavior. This example does not use the transmitter.

{{/annotated?}}
(defn render-page [renderer [_ path] transmitter]
  (let [{{#annotated?}};; The renderer that we are using here helps us map changes to
        ;; the UI tree to the DOM. It keeps a mapping of paths to DOM
        ;; ids. The `get-parent-id` function will return the DOM id of
        ;; the parent of the node at path. If the path is [:a :b :c]
        ;; then this will find the id associated with [:a :b]. The
        ;; root node [] is configured when we created the renderer.
        {{/annotated?}}parent (render/get-parent-id renderer path)
        {{#annotated?}};; Use the `new-id!` function to associate a new id to the
        ;; given path. With two arguments, this function will generate
        ;; a random unique id. With three arguments, the given id will
        ;; be associated with the given path.
        {{/annotated?}}id (render/new-id! renderer path)
        {{#annotated?}};; Get the dynamic template named :{{name}}-page
        ;; from the templates map. The `add-template` function will
        ;; associate this template with the node at
        ;; path. `add-template` returns a function that can be called
        ;; to generate the initial HTML.
        {{/annotated?}}html (templates/add-template renderer path (:{{name}}-page templates))]
    {{#annotated?}}
    ;; Call the `html` function, passing the initial values for the
    ;; template. This returns an HTML string which is then added to
    ;; the DOM using Domina.
    {{/annotated?}}
    (dom/append! (dom/by-id parent) (html {:id id :message ""}))))

(defn render-message [renderer [_ path _ new-value] transmitter]
  {{#annotated?}}
  ;; This function responds to a :value event. It uses the
  ;; `update-t` function to update the template at `path` with the new
  ;; values in the passed map.
  {{/annotated?}}
  (templates/update-t renderer path {:message new-value}))

{{#annotated?}}
;; The data structure below is used to map rendering data to functions
;; which handle rendering for that specific change. This function is
;; referenced in config/config.edn and must be a function in order to
;; be used from the tool's "render" view.

{{/annotated?}}
(defn render-config []
  [{{#annotated?}};; All :node-create deltas for the node at :greeting will
   ;; be rendered by the `render-page` function. The node name
   ;; :greeting is a default name that is used when we don't
   ;; provide our own derives and emits. To name your own nodes,
   ;; create a custom derive or emit in the application's behavior.
   {{/annotated?}}[:node-create  [:greeting] render-page]
   {{#annotated?}};; All :node-destroy deltas for this path will be handled by the
   ;; library function `d/default-exit`.
   {{/annotated?}}[:node-destroy   [:greeting] d/default-exit]
   {{#annotated?}};; All :value deltas for this path will be handled by the
   ;; function `render-message`.
   {{/annotated?}}[:value [:greeting] render-message]])
{{#annotated?}}

;; In render-config, paths can use wildcard keywords :* and :**. :*
;; means exactly one segment with any value. :** means 0 or more
;; elements.
{{/annotated?}}
