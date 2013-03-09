(ns {{name}}.test.behavior
  (:require [io.pedestal.app :as app]
            [io.pedestal.app.protocols :as p]
            [io.pedestal.app.tree :as tree]
            [io.pedestal.app.messages :as msg]
            [io.pedestal.app.render :as render]
            [io.pedestal.app.util.test :as test])
  (:use clojure.test
        {{name}}.behavior
        [io.pedestal.app.query :only [q]]))

;; Test a model function

(deftest test-example-model
  (is (= (example-model {} {:value "x"})
         "x")))

;; Build an application, send a message to a model and check the model
;; state

(deftest test-app-state
  (let [app (app/build example-app)]
    (app/begin app)
    (is (true? (test/run-sync! app [{msg/topic :example-model :value "x"}])))
    (is (= (-> app :state deref :models :example-model) "x"))))

;; Use io.pedestal.app.query to query the current application model

(deftest test-query-ui
  (let [app (app/build example-app)
        app-model (render/consume-app-model app (constantly nil))]
    (app/begin app)
    (is (test/run-sync! app [{msg/topic :example-model :value "x"}]))
    (is (= (q '[:find ?v
                :where
                [?n :t/path [:io.pedestal.app/view-example-model]]
                [?n :t/value ?v]]
              @app-model)
           [["x"]]))))
