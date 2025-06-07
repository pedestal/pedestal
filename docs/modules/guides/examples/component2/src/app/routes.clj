(ns app.routes)                                             ;; <1>

(defn routes
  [component]
  (let [{:keys [get-greeting]} component]                   ;; <2>
    #{["/api/greet" :get get-greeting]}))                   ;; <3>
