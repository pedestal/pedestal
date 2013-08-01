(ns {{namespace}}.test.behavior
  (:require [io.pedestal.app :as app]
            [io.pedestal.app.protocols :as p]
            [io.pedestal.app.tree :as tree]
            [io.pedestal.app.messages :as msg]
            [io.pedestal.app.render :as render]
            [io.pedestal.app.util.test :as test])
  (:use clojure.test
        {{namespace}}.behavior
        [io.pedestal.app.query :only [q]]))

;; Test a transform function

(deftest test-set-value-transform
  (is (= (set-value-transform {} {::msg/type :set-value ::msg/topic [:greeting] :value "x"})
         "x")))

;; Build an application, send a message to a transform and check the transform
;; state

(deftest test-app-state
  (let [app (app/build example-app)]
    (app/begin app)
    (is (vector?
         (test/run-sync! app [{::msg/type :set-value ::msg/topic [:greeting] :value "x"}])))
    (is (= (-> app :state deref :data-model :greeting) "x"))))

;; Use io.pedestal.app.query to query the current application model

(deftest test-query-ui
  (let [app (app/build example-app)
        app-model (render/consume-app-model app (constantly nil))]
    (app/begin app)
    (is (test/run-sync! app [{::msg/topic [:greeting] ::msg/type :set-value :value "x"}]))
    (is (= (q '[:find ?v
                :where
                [?n :t/path [:greeting]]
                [?n :t/value ?v]]
              @app-model)
           [["x"]]))))

