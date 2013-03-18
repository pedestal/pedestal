; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app-tools.rendering-view.record
  (:require [domina :as dom]
            [cljs.reader :as reader]
            [goog.events :as gevents]
            [goog.events.KeyCodes :as kc]
            [goog.events.KeyHandler :as kh]
            [io.pedestal.app.util.observers :as observers]
            [io.pedestal.app.tree :as tree]
            [io.pedestal.app.messages :as msg]
            [io.pedestal.app.net.xhr :as xhr]
            [io.pedestal.app.protocols :as p]
            [io.pedestal.app.render.push.handlers.automatic :as d]))

(def recording-state (atom {:recording? false :ui nil}))

(defn- app-model-to-deltas [current-app-model]
  (let [empty-tree tree/new-app-model
        t0 (tree/t empty-tree)
        new-tree (tree/apply-deltas empty-tree [current-app-model])]
    (.log js/console (pr-str current-app-model))
    (.log js/console (pr-str (tree/expand-map current-app-model)))
    (.log js/console (pr-str t0))
    (.log js/console (pr-str new-tree))
    (.log js/console (pr-str (vec (tree/since-t new-tree t0))))
    (vec (tree/since-t new-tree t0))))

(defn maybe-init-recording [state]
  (if (:recording? state)
    (-> state
        (assoc :t (get-in state [:ui :t]))
        (assoc :recorded-deltas (app-model-to-deltas (get-in state [:ui :tree]))))
    state))

(defn update-when-recording [state]
  (if (:recording? state)
    (-> state
        (update-in [:recorded-deltas] conj :break)
        (update-in [:recorded-deltas] into (tree/since-t (:ui state) (:t state)))
        (assoc :t (get-in state [:ui :t])))
    state))

(defn toggle-recording* [state]
  (-> state
      (update-in [:recording?] not)
      (maybe-init-recording)))

(defn update-ui [state new-app-model]
  (-> state
      (assoc :ui new-app-model)
      (update-when-recording)))

(defn save-recording [recording]
  (let [recording-name (get-in recording [:config :name])]
    (xhr/request (gensym)
                 (str "/_tools/render/recordings/" (name recording-name))
                 :headers {"Content-Type" "application/edn"}
                 :request-method "POST"
                 :body (pr-str recording)
                 :on-success (constantly nil)
                 :on-error (constantly nil))))

(defmethod d/modal-title ::recording-info [transform-name messages]
  "Configure Recording")

(defn valid-position? [x]
  (let [p (js/parseInt x 10)]
    (and (number? p) (not (neg? p)))))

(defn non-empty-string? [x]
  (not (empty? x)))

(defn keyword-string? [x]
  (keyword? (try (reader/read-string x)
                 (catch js/Error _ nil))))

(defmethod d/modal-field [::recording-info "description"] [_ _]
  {:field-name "Description:"
   :placeholder "Enter description"
   :input-class "input-xlarge"
   :validation-fn non-empty-string?
   :inline-help-error "Description is required"})

(defmethod d/modal-field [::recording-info "name"] [_ _]
  {:field-name "Name Keyword:"
   :placeholder "Enter name (keyword)"
   :input-class "input-xlarge"
   :validation-fn keyword-string?
   :inline-help-error "Name must be a keyword"})

(defmethod d/modal-field [::recording-info "order"] [_ _]
  {:field-name "Position:"
   :placeholder "position in list"
   :input-class "input-mini"
   :default 0
   :validation-fn valid-position?
   :inline-help ""
   :inline-help-error "Position must be a number >= 0"})

(defn make-and-save-recording [message]
  (let [{:keys [name description order]} message]
    (save-recording {:config {:order (js/parseInt order)
                              :description description
                              :name (reader/read-string name)}
                     :data (:recorded-deltas @recording-state)})))

(defn- recording? []
  (:recording? @recording-state))

(defn- display-recording-state []
  (let [node (dom/by-id "pedestal-status-panel")]
    (if (recording?)
      (do (dom/set-html! node
                         (str "<div>Recording...</div>"
                              "<div class='pedestal-recording-status-message'>"
                              "Use [ESC] to cancel or Alt-Shift-R to stop"
                              "</div>"))
          (dom/set-style! node :opacity 0.8)
          (dom/add-class! node "pedestal-recording-status"))
      (do (dom/set-html! node "")
          (dom/set-style! node :opacity 0)
          (dom/remove-class! node "pedestal-recording-status")))))

(defn- toggle-recording-internal []
  (let [r? (:recording? (swap! recording-state toggle-recording*))]
    (display-recording-state)
    r?))

(defn toggle-recording []
  (let [recording? (toggle-recording-internal)]
    (when (not recording?)
      (d/generic-modal-collect-input "content"
                                     (gensym)
                                     (reify p/PutMessage
                                       (put-message [_ message]
                                         (make-and-save-recording message)))
                                     ::recording-info
                                     [{(msg/param :name) {}
                                       (msg/param :description) {}
                                       (msg/param :order) {}}]))))

(def docKh (goog.events.KeyHandler. js/document))

(defn key-handler [e]
  (let [keys [(.-keyCode e) (.-shiftKey e) (.-altKey e)]]
    (when (= keys [goog.events.KeyCodes/R true true])
      (toggle-recording))
    (when (and (recording?) (= (.-keyCode e) goog.events.KeyCodes/ESC))
      (toggle-recording-internal))))

(defn init-recording [app-model]
  (gevents/listen docKh "key" key-handler)
  (swap! recording-state update-ui @app-model)
  (add-watch app-model :recording
             (fn [_ _ _ n]
               (swap! recording-state update-ui n))))
