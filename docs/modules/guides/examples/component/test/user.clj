(ns user
  (:require [com.stuartsierra.component :as component]
            clj-reload.core                                 ;; <1>
            app.system))

(defonce *system (atom nil))                                ;; <2>

(defn start!
  []
  (reset! *system
          (-> (app.system/new-system)
              (component/start-system))))

(defn stop!
  []
  (when-let [system @*system]
    (component/stop-system system))
  (reset! *system nil))


