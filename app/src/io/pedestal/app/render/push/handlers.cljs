; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.app.render.push.handlers
  (:require [io.pedestal.app.util.log :as log]
            [io.pedestal.app.render.push :as render]
            [io.pedestal.app.render.events :as events]
            [domina :as d]
            [domina.events :as event]))

(defn add-send-on [event-type dom-content]
  (fn [renderer [_ _ transform-name messages] input-queue]
    (events/send-on event-type dom-content input-queue transform-name messages)))

(defn add-send-on-click [dom-content]
  (add-send-on :click dom-content))

(defn remove-send-on [event-type dom-content]
  (fn [renderer [_ _ transform-name messages] input-queue]
    (events/remove-event event-type dom-content)))

(defn remove-send-on-click [dom-content]
  (remove-send-on :click dom-content))

(defn destroy! [r path]
  (if-let [id (render/get-id r path)]
    (do (render/delete-id! r path)
        (d/destroy! (d/by-id id)))
    (log/warn :in :default-exit :msg (str "warning! no id " id " found for path " (pr-str path)))))

(defn default-destroy [r [_ path] _]
  (destroy! r path))
