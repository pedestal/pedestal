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

(deftest test-example-transform
  (is (= (example-transform {} {msg/type msg/init, :value "x"})
         "x")))

(deftest test-app-state
  (let [app (app/build example-app)]
    (app/begin app)
    (is (vector?
         (test/run-sync! app [{msg/topic :example-transform, msg/type msg/init, :value "x"}])))
    (is (= (-> app :state deref :models :example-transform) "x"))))

(deftest test-query-ui
  (let [app (app/build example-app)
        app-model (render/consume-app-model app (constantly nil))]
    (app/begin app)
    (is (test/run-sync! app [{msg/topic :example-transform, msg/type msg/init, :value "x"}]))
    (is (= (q '[:find ?v
                :where
                [?n :t/path [:io.pedestal.app/view-example-transform]]
                [?n :t/value ?v]]
              @app-model)
           [["x"]]))))
