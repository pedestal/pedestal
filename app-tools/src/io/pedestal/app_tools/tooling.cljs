; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

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
