(ns {{name}}.start
  (:require [io.pedestal.app.protocols :as p]
            [io.pedestal.app :as app]
            [io.pedestal.app.render.push :as push-render]
            [io.pedestal.app.render :as render]
            [{{name}}.behavior :as behavior]
            [{{name}}.rendering :as rendering]))

;; In this namespace, the application is built and started.

(defn create-app [render-config]
  (let [;; Build the application described in the map
        ;; 'behavior/example-app'. The application is a record which
        ;; implements the Receiver protocol.
        app (app/build behavior/example-app)
        ;; Create the render function that will be used by this
        ;; application. A renderer function takes two arguments: the
        ;; application model deltas and the input queue.
        ;;
        ;; On the line below, we create a renderer that will help in
        ;; mapping UI data to the DOM. 
        ;;
        ;; The file, app/src/{{sanitized}}/rendering.cljs contains
        ;; the code which does all of the rendering as well as the
        ;; render-config which is used to map renderering data to
        ;; specific functions.
        render-fn (push-render/renderer "content" render-config)
        ;; This application does not yet have services, but if it did,
        ;; this would be a good place to create it.
        ;; services-fn (fn [message input-queue] ...)

        ;; Configure the application to send all rendering data to this
        ;; renderer.
        app-model (render/consume-app-model app render-fn)]
    ;; If services existed, configure the application to send all
    ;; effects there.
    ;; (app/consume-effect app services-fn)
    ;;
    ;; Start the application
    (app/begin app)
    ;; Returning the app and app-model from the main function allows
    ;; the tooling to add support for useful features like logging
    ;; and recording.
    {:app app :app-model app-model}))

(defn ^:export main []
  ;; config/config.clj refers to this namespace as a main namespace
  ;; for several aspects. A main namespace must have a no argument
  ;; main function. To tie into tooling, this function should return
  ;; the newly created app.
  (create-app (rendering/render-config)))
