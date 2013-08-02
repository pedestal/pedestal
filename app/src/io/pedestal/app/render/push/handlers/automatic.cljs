; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.render.push.handlers.automatic
  (:require [cljs.reader :as reader]
            [io.pedestal.app.util.log :as log]
            [io.pedestal.app.render.push :as render]
            [io.pedestal.app.messages :as msg]
            [io.pedestal.app.render.push.cljs-formatter :as formatter]
            [io.pedestal.app.render.events :as events]
            [io.pedestal.app.render.push.templates :as templates]
            [domina :as d]
            [domina.events :as event]))

(defn- prompt-values [syms]
  (zipmap syms
          (mapv #(js/prompt (str "Enter value for: " (name %))) syms)))

(defn get-missing-input [messages]
  (let [syms (msg/message-params messages)]
    (if (seq syms)
      (fn [_]
        (let [env (prompt-values syms)]
          (msg/fill-params env messages)))
      messages)))

(defn- modal-id [id transform-name]
  (str id "-modal-" (name transform-name)))

(defn- modal-continue-button-id [id transform-name]
  (str (modal-id id transform-name) "-continue"))

(defn- modal-field-id [id transform-name sym]
  (str (modal-id id transform-name) "-field-" (name sym)))

(defmulti modal-title (fn [transform-name messages] transform-name))

(defmethod modal-title :default [transform-name _]
  (pr-str transform-name))

(defmulti modal-content (fn [transform-name messages] transform-name))

(defmethod modal-content :default [transform-name _]
  "")

(defmulti modal-field (fn [transform-name field-name] [transform-name field-name]))

(defmethod modal-field :default [_ field-name]
  {:field-name (str field-name ":")
   :placeholder (str "Enter " field-name)
   :input-class "input-xlarge"
   :default nil
   :validation-fn (fn [x] (not (or (nil? x) (= x ""))))
   :inline-help ""
   :inline-help-error (str field-name " is required")})

(defn modal-input-field [id transform-name sym]
  (let [{:keys [field-name placeholder input-class default inline-help]} (modal-field transform-name
                                                                                      (name sym))
        field-id (modal-field-id id transform-name sym)]
    (str "<label class='control-label' for='" field-id "'>" field-name "</label>"
         "<div class='controls'>"
         "<input id='" field-id "' "
         "       class='" input-class "' type='text' placeholder='" placeholder "'"
         (when default (str " value='" default "'"))
         ">"
         "<span class='help-inline' id='" field-id "-help-inline'>" inline-help "</span>"
         "</div>")))

(defn modal-input-html [id transform-name messages]
  (let [syms (msg/message-params messages)]
    (when (seq syms)
      (let [modal-id (modal-id id transform-name)
            continue-button-id (modal-continue-button-id id transform-name)]
        (str "<div class='modal hide fade' id='" modal-id "' tabindex='-1' role='dialog'"
             "     aria-labelledby='" modal-id "Label' aria-hidden='true'>"
             "  <div class='modal-header'>"
             "    <button type='button' class='close' data-dismiss='modal'"
             "            aria-hidden='true'>×</button>"
             "    <h3 id='" modal-id "Label'>" (modal-title transform-name messages) "</h3>"
             "  </div>"
             "  <div class='modal-body'>"
             (modal-content transform-name)
             "<div class='control-group' id='modal-control-group'>"
             "    <form onsubmit='return false;'>"
                    (apply str (map (partial modal-input-field id transform-name) syms))
             "    </form>"
             "  </div>"
             "</div>"
             "  <div class='modal-footer'>"
             "    <button class='btn' data-dismiss='modal' aria-hidden='true'>Cancel</button>"
             "    <button class='btn btn-primary' id='" continue-button-id "'>Continue</button>"
             "  </div>"
             "</div>")))))

(defn- get-modal-value [id transform-name sym]
  (let [field-id (modal-field-id id transform-name sym)
        value (.-value (d/by-id field-id))
        {:keys [validation-fn inline-help-error]} (modal-field transform-name (name sym))]
    (if (validation-fn value)
      {:value value}
      {:value value :error true :field-id field-id :message inline-help-error})))

(defn- get-modal-values [id transform-name syms]
  (reduce (fn [a sym]
            (let [v (get-modal-value id transform-name sym)]
              (if (:error v)
                (assoc-in a [:errors sym] v)
                (assoc-in a [:env sym] v))))
          {:env {}}
          syms))

(defn- hide-and-return-messages [id transform-name messages]
  (js/hideModal (modal-id id transform-name))
  messages)

(defn- highlight-errors [errors]
  (doseq [{:keys [field-id message]} (vals errors)]
    (d/add-class! (d/by-id "modal-control-group") "error")
    (d/set-text! (d/by-id (str field-id "-help-inline"))
                 message)))

(defn- submit-dialog-fn [id transform-name messages]
  (let [syms (msg/message-params messages)
        messages-hash (into {} (for [m messages [k v] m] [k v]))
        read-value (fn [k v]
                     (let [{read-as :read-as} (k messages-hash)]
                       (if (= read-as :data)
                         (reader/read-string v)
                         v)))]
    (fn [_]
      (if (seq syms)
        (let [values (get-modal-values id transform-name syms)]
          (if (:errors values)
            (do (highlight-errors (:errors values))
                [])
            (hide-and-return-messages id
                                      transform-name
                                      (msg/fill-params (reduce (fn [a [k v]]
                                                                 (assoc a k (read-value k (:value v))))
                                                               {}
                                                               (:env values))
                                                       messages))))
        (hide-and-return-messages id transform-name messages)))))

(defn generic-modal-collect-input [parent-id id input-queue transform-name messages]
  (let [modal-continue-button-id (modal-continue-button-id id transform-name)]
    (d/append! (d/by-id parent-id)
               (modal-input-html id transform-name messages))
    (events/send-on-click (d/by-id modal-continue-button-id)
                      input-queue
                      (submit-dialog-fn id transform-name messages))
    (js/showModal (modal-id id transform-name))))

(defn modal-collect-input [r input-queue path transform-name messages]
  (let [path (conj path :modal)
        parent-id (render/get-parent-id r path)
        id (render/new-id! r path)]
    (generic-modal-collect-input parent-id id input-queue transform-name messages)))

(defn render-event-enter [r [_ path transform-name messages] input-queue]
  (let [control-id (render/get-id r (conj path "control"))
        button-id (render/new-id! r (conj path "control" transform-name))]
    (let [messages (map (partial msg/add-message-type transform-name) messages)
          syms (msg/message-params messages)]
      (assert input-queue "Input-Queue is nil")
      (d/append! (d/by-id control-id)
                 (str "<a class='btn btn-primary' style='margin-top:5px;margin-right:5px;' "
                      "id='" button-id "'>"
                      (str transform-name)
                      "</a>"))
      (if (seq syms)
        ;; Open the modal dialog for this event
        (event/listen! (d/by-id button-id)
                       :click
                       (fn [e]
                         (event/prevent-default e)
                         (modal-collect-input r input-queue path transform-name messages)))
        ;; Gather input and send messages
        (events/send-on-click (d/by-id button-id)
                          input-queue
                          (get-missing-input (mapv #(assoc % :from :ui) messages))))

      (render/on-destroy! r path #(event/unlisten! (d/by-id button-id) :click)))))

(defn render-node-enter [r [_ path] input-queue]
  (let [parent (render/get-parent-id r path)
        id (render/new-id! r path)
        data-id (render/new-id! r (conj path "data"))
        control-id (render/new-id! r (conj path "control"))
        path-length (count path)]
    (d/append! (d/by-id parent)
               (str "<div id='" id "' class='" (if (<= path-length 1)
                                                 "root-node-section"
                                                 "node-section") "'>"
                    "  <div class='row-fluid'>"
                    "    <div class='span3' style='text-align:right' id='" control-id "'></div>"
                    "    <div class='span9'>"
                    "      <h4 class='muted'>" (last path) "</h4>"
                    "      <div id='" data-id "'></div>"
                    "    </div>"
                    "  </div>"
                    "</div>"))))

(defn render-value-update [r [_ path _ v] d]
  (let [data-id (render/get-id r (conj path "data"))
        container (d/single-node (d/by-id data-id))]
    (d/destroy-children! container)
    (if v
      (let [expression (d/single-node (formatter/html v))]
        (d/append! container expression)
        (formatter/arrange! expression container)))))

(defn div-with-id [id]
  (fn [r [_ path] d]
    (let [parent (render/get-parent-id r path)
          id (render/new-id! r path id)]
      (d/append! (d/by-id parent) (str "<div id='" id "'></div>")))))

(defn append-to-parent [f]
  (fn [r [_ path] d]
    (let [parent (render/get-parent-id r path)
          id (render/new-id! r path)]
      (d/append! (d/by-id parent) (f id)))))

(defn prepend-to-parent [f]
  (fn [r [_ path] d]
    (let [parent (render/get-parent-id r path)
          id (render/new-id! r path)]
      (d/prepend! (d/by-id parent) (f id)))))

(defn append-value [f]
  (fn [r [_ path v] d]
    (let [id (render/get-id r path)]
      (d/append! (d/by-id id) (f v)))))

(defn attach-click-event [id transform-name messages input-queue]
  (let [messages (map (partial msg/add-message-type transform-name) messages)]
    (events/send-on-click (d/by-id id)
                          input-queue
                          (get-missing-input messages))))

(defn event-enter
  ([]
     (event-enter nil))
  ([modal-path]
     (fn [r [_ path transform-name messages] input-queue]
       (let [modal-path (or modal-path path)
             item-id (render/get-id r path)]
         (let [messages (map (partial msg/add-message-type transform-name) messages)
               syms (msg/message-params messages)]
           (if (seq syms)
             (event/listen! (d/by-id item-id)
                            :click
                            (fn [e]
                              (event/prevent-default e)
                              ;; TODO: The modal dialog may be added to
                              ;; the id that maps to modal-path. This means that the
                              ;; dialog is not tied to any node and will
                              ;; not be deleted when a node is
                              ;; deleted.
                              (modal-collect-input r input-queue modal-path transform-name messages)))
             (events/send-on-click (d/by-id item-id)
                                   input-queue
                                   (get-missing-input messages))))))))

(defn event-exit [r [_ path transform-name] _]
  (let [node-id (render/get-id r path)
        default-button-id (render/get-id r (conj path "control" transform-name))
        id (or default-button-id node-id)]
    (when id
      (event/unlisten! (d/by-id id) :click))
    (when default-button-id
      (d/destroy! (d/by-id default-button-id)))))

;; deprecated - use io.pedestal.app.render.push.handlers/destroy!
(defn destroy! [r path]
  (if-let [id (render/get-id r path)]
    (do (render/delete-id! r path)
        (d/destroy! (d/by-id id)))
    (log/warn :in :default-exit :msg (str "warning! no id " id " found for path " (pr-str path)))))

;; deprecated - use io.pedestal.app.render.push.handlers/default-destroy
(defn default-exit [r [_ path] d]
  (destroy! r path))

(defn sync-class! [pred id class-name]
  (let [element (d/by-id id)]
    (if pred
      (when (not (d/has-class? element class-name))
        (d/add-class! element class-name))
      (when (d/has-class? element class-name)
        (d/remove-class! element class-name)))))

(def data-renderer-config
  [[:node-create    []    (constantly nil)]
   [:node-destroy   []    (constantly nil)]
   [:node-create    [:**] render-node-enter]
   [:node-destroy   [:**] default-exit]
   [:value          [:**] render-value-update]
   [:attr           [:**] (constantly nil)]
   [:transform-enable  [:**] render-event-enter]
   [:transform-disable [:**] event-exit]])
